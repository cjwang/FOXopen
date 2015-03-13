package net.foxopen.fox.database.storage.dom;

import net.foxopen.fox.ContextUCon;
import net.foxopen.fox.XFUtil;
import net.foxopen.fox.database.UCon;
import net.foxopen.fox.database.sql.ExecutableAPI;
import net.foxopen.fox.database.storage.WaitingRowSelector;
import net.foxopen.fox.dom.DOM;
import net.foxopen.fox.ex.ExDB;
import net.foxopen.fox.ex.ExDBTimeout;
import net.foxopen.fox.ex.ExInternal;
import net.foxopen.fox.thread.storage.StatementType;
import net.foxopen.fox.thread.storage.WorkingDataDOMStorageLocation;
import net.foxopen.fox.track.Track;

import java.util.Iterator;


/**
 * Writeable implementation of an XMLWorkDoc. This object must be conceptually locked before any modifications are made to its state.
 * Note this is not achieved with Java locking so extra care must be taken that unwanted modifications are note made to the object.
 */
public class WriteableXMLWorkDoc
extends XMLWorkDoc {

  private static final int RECORD_LOCKED_TIMEOUT_SECS = 8;
  private static final Iterator gUniqueIterator = XFUtil.getUniqueIterator();
  private static final WaitingRowSelector gRowSelector = new WaitingRowSelector(RECORD_LOCKED_TIMEOUT_SECS);

  /** Flag indicating if this WorkDoc is currently open. */
  private boolean mIsOpen;

  /** DOM modify count when the WorkDoc was open, used for tracking if the DOM has changed. */
  private int mDOMModifyCountAtOpen;

  /** Flag indicating if the DOM should have Auto IDs set or not */
  private final boolean mIsAutoIds;

  WriteableXMLWorkDoc(WorkingDataDOMStorageLocation pWorkingStoreLocation, boolean pIsAutoIds, XMLWorkDocDOMAccessor pDOMAccessor) {
    super(pWorkingStoreLocation, pDOMAccessor);
    mIsAutoIds = pIsAutoIds;
  }

  @Override
  public void open(ContextUCon pContextUCon) {

    Track.pushInfo("WorkDocOpen", getDOMAccessor().getClass().getSimpleName());
    try {
      UCon lUCon = pContextUCon.getUCon("WorkDoc Open");
      try {
        //Attempt to select the target row - this should get a row lock which is important for transactional integrity
        //during the open operation.
        //IMPORTANT: this is giving us a conceptual object lock for this WorkDoc (similar to how entitlements used to work)
        //Any changes to this routine must be carefully tested to ensure the lock is still acquired at the correct point.
        //In particualar no change to object state should occur until the database lock is acquired.
        boolean lRowExists = openLocatorInWaitLoop(lUCon);

        //Don't do the open check until we have a lock - effectively synchronising access to this object
        if(isOpen()) {
          throw new IllegalStateException("WorkDoc is already open");
        }

        //If a row was selected above
        if(lRowExists) {
          readExistingRow(lUCon);
        }
        else {
          //No row found - use the insert statement if defined
          insertNewRow(lUCon);
        }

        //Set correct actuator
        if(mIsAutoIds) {
          getDOM().getDocControl().setDocumentReadWriteAutoIds();
        }
        else {
          //Default is read write no auto IDs
          getDOM().getDocControl().setDocumentReadWrite();
        }

        mIsOpen = true;
        Track.info("WorkDocCacheKey", getCacheKey());
      }
      finally {
        pContextUCon.returnUCon(lUCon, "WorkDoc Open");
      }
    }
    finally {
      Track.pop("WorkDocOpen");
    }
  }

  @Override
  public void close(ContextUCon pContextUCon) {

    if(!isOpen()) {
      throw new IllegalStateException("WorkDoc cannot be closed if not open");
    }

    Track.pushInfo("WorkDocClose", getDOMAccessor().getClass().getSimpleName());
    try {
      UCon lUCon = pContextUCon.getUCon("WorkDoc Close");
      try {
        DOM lDOM = getDOM();
        //If the DOM was modified it needs to be written back
        if(mDOMModifyCountAtOpen != lDOM.getDocumentModifiedCount()) {

          // Update document's change number
          String lChangeNumber = updateChangeNumberOnDOM();
          Track.info("ChangeNumberAtClose", lChangeNumber);

          // Update record information
          updateRow(lUCon);

          //Do this after update as updateRow() may change the DOM (currently for binary XML)
          mDOMModifyCountAtOpen = lDOM.getDocumentModifiedCount();
        }
        else {
          Track.info("UpdateSkipped", "DOM not modified; update not required");
        }

        Track.debug("DOM to RO for " + lDOM.getRootElement().getName());
        lDOM.getDocControl().setDocumentReadOnly();
      }
      finally {
        mIsOpen = false;
        getDOMAccessor().closeLocator(lUCon);
        pContextUCon.returnUCon(lUCon, "WorkDoc Close");
      }
    }
    finally {
      Track.pop("WorkDocClose");
    }
  }

  @Override
  protected void abortInternal() {
    //Note: this used to free the temp CLOB
    mIsOpen = false;
  }

  private boolean openLocatorInWaitLoop(UCon pUCon) {

    WaitingRowSelector.SelectAction lSelectAction = new WaitingRowSelector.SelectAction() {
      public boolean attemptSelect(UCon pUCon) throws ExDBTimeout {
        return selectRowAndOpenLocator(pUCon);
      }
    };

    return gRowSelector.selectRow(pUCon, lSelectAction, getWorkingStoreLocation().getStorageLocationName());
  }

  /**
   * Handles the reading behaviour if a row was successfully selected by the SELECT statement. Checks the change number
   * and reads the DOM into memory if necessary.
   * @param pUCon
   */
  private void readExistingRow(UCon pUCon) {
    if(getDOMAccessor().isLocatorEmpty(pUCon) || getDOMAccessor().isLocatorNull(pUCon)) {
      //LOB locator is empty, initialise a new document
      Track.info("EmptyLOBLocator", "Empty LOB on row; initialising new document");
      DOM lNewDOM = initialiseDOM();
      setDOM(lNewDOM);
      setDOMChangeNumber("*UNKNOWN*");
      mDOMModifyCountAtOpen = lNewDOM.getDocumentModifiedCount() - 1; // forces an update on close()
    }
    else {
      readNonEmptyExistingRow(pUCon);
      mDOMModifyCountAtOpen = getDOM().getDocumentModifiedCount();
    }
  }

  /**
   * Constructs a new DOM document based on the root element name specified in this WorkDoc's WSL.
   * @return New document with correct root element name.
   */
  private DOM initialiseDOM() {
    String lNewDocRootName = getWorkingStoreLocation().getStorageLocation().getNewDocRootElementName();
    if(lNewDocRootName == null) {
      throw new ExInternal("Storage Location " + getWorkingStoreLocation().getStorageLocationName() + " has no root element name, cannot initialise");
    }
    DOM lDOM = DOM.createDocument(lNewDocRootName);
    return lDOM;
  }


  private void updateRow(UCon pUCon) {
    //Call the abstract method to write the DOM
    getDOMAccessor().prepareForDML(pUCon, getDOM());

    //Run the update statement if defined
    runUpdateStatement(pUCon);

    //Check the correct row was updated
    boolean lRowExists = openLocatorInWaitLoop(pUCon);

    //If a row was selected above and the LOB is neither empty nor null
    if(lRowExists && !(getDOMAccessor().isLocatorEmpty(pUCon) || getDOMAccessor().isLocatorNull(pUCon))) {
      String lWrittenChangeNum = getDOMAccessor().readChangeNumber(pUCon);

      String lDOMChangeNumber = getDOMChangeNumber();
      if (!lDOMChangeNumber.equals(lWrittenChangeNum)) {
        throw new ExInternal("Storage Location: Update/Query pair do not access the same row/column (change number inconsistent - DOM=" + lDOMChangeNumber+  ", Database=" + lWrittenChangeNum + "): "
                             + getWorkingStoreLocation().getStorageLocationName());
      }
    }
    else {
      throw new ExInternal("Storage Location: Update/Query pair do not access the same row/column or failed to update column (column still null): " + getWorkingStoreLocation().getStorageLocationName());
    }
  }

  private void insertNewRow(UCon pUCon) {

    Track.pushDebug("InsertRow");
    try {
      if(getWorkingStoreLocation().getStorageLocation().hasDatabaseStatement(StatementType.INSERT)) {
        DOM lNewDOM = initialiseDOM();
        setDOM(lNewDOM);

        //Call the abstract method to write the DOM
        getDOMAccessor().prepareForDML(pUCon, lNewDOM);

        //Record status now to prevent unnecessary update if the DOM doesn't change between WorkDoc open and WorkDoc close
        updateChangeNumberOnDOM();
        mDOMModifyCountAtOpen = lNewDOM.getDocumentModifiedCount();

        //Run the update statement if defined
        runInsertStatement(pUCon);

        //Check the correct row was inserted and ensure we now have a reference to the LOB pointer for writing later
        boolean lRowExists = openLocatorInWaitLoop(pUCon);

        //If a row was selected above (note locator may still be empty), check the change number
        if(lRowExists && !getDOMAccessor().isLocatorNull(pUCon)) {

          if(getDOMAccessor().isLocatorEmpty(pUCon)) {
            //Locator was still empty (i.e. empty_clob insert), force an update on close
            mDOMModifyCountAtOpen = mDOMModifyCountAtOpen - 1;
          }
        }
        else if(!lRowExists){
          throw new ExInternal("Storage Location: Insert/Query pair do not access the same row/column (no data found): " + getWorkingStoreLocation().getStorageLocationName());
        }
      }
      else {
        throw new ExInternal("No row returned and no insert statement defined for storage location " + getWorkingStoreLocation().getStorageLocationName());
      }
    }
    finally {
      Track.pop("InsertRow");
    }
  }

  private String updateChangeNumberOnDOM() {
    String lDOMChangeNumber = (String) gUniqueIterator.next();
    getDOM().setAttr(CHANGE_NUMBER_ATTR_NAME, lDOMChangeNumber);
    setDOMChangeNumber(lDOMChangeNumber);
    return lDOMChangeNumber;
  }

  private void runInsertStatement(UCon pUCon) {
    ExecutableAPI lInsertStatement = getWorkingStoreLocation().getExecutableInsertStatementOrNull(this);
    try {
      lInsertStatement.executeAndClose(pUCon);
    }
    catch (ExDB e) {
      throw new ExInternal("Failed to run insert statement for storage location " + getWorkingStoreLocation().getStorageLocationName(), e);
    }
  }

  private void runUpdateStatement(UCon pUCon) {

    ExecutableAPI lUpdateStatement = getWorkingStoreLocation().getExecutableUpdateStatementOrNull(this);
    if(lUpdateStatement != null) {
      try {
        lUpdateStatement.executeAndClose(pUCon);
      }
      catch (ExDB e) {
        throw new ExInternal("Failed to run update statement for storage location " + getWorkingStoreLocation().getStorageLocationName(), e);
      }
    }
  }

  @Override
  public boolean isOpen() {
    return mIsOpen;
  }
}