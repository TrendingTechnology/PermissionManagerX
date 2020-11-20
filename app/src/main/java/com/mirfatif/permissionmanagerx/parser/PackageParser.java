package com.mirfatif.permissionmanagerx.parser;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.mirfatif.permissionmanagerx.App;
import com.mirfatif.permissionmanagerx.MySettings;
import com.mirfatif.permissionmanagerx.Package;
import com.mirfatif.permissionmanagerx.PermGroupsMapping;
import com.mirfatif.permissionmanagerx.PermGroupsMapping.GroupOrderPair;
import com.mirfatif.permissionmanagerx.Permission;
import com.mirfatif.permissionmanagerx.PrivDaemonHandler;
import com.mirfatif.permissionmanagerx.R;
import com.mirfatif.permissionmanagerx.Utils;
import com.mirfatif.permissionmanagerx.permsdb.PermissionEntity;
import com.mirfatif.privdaemon.MyPackageOps;
import com.mirfatif.privdaemon.PrivDaemon;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

public class PackageParser {

  static final String TAG = "PackageParser";

  private static PackageParser mPackageParser;
  private PackageManager mPackageManager;
  private MySettings mMySettings;
  private AppOpsParser mAppOpsParser;
  private PrivDaemonHandler mPrivDaemonHandler;
  private PermGroupsMapping mPermGroupsMapping;

  private final MutableLiveData<List<Package>> mPackagesListLive = new MutableLiveData<>();
  private final MutableLiveData<Package> mChangedPackage = new MutableLiveData<>();
  private final MutableLiveData<Integer> mProgressMax = new MutableLiveData<>();
  private final MutableLiveData<Integer> mProgressNow = new MutableLiveData<>();

  private List<PackageInfo> packageInfoList;
  private List<Package> mPackagesList = new ArrayList<>();
  private List<Signature> systemSignatures;
  private final Map<String, Drawable> mPackageIconsList = new HashMap<>();
  private final Map<String, Integer> mPermIconsResIds = new HashMap<>();
  private List<Integer> mOpToSwitchList;
  private List<Integer> mOpToDefModeList;
  private Map<String, String> mPermRefList;
  private Map<String, Integer> mPermToOpCodeMap;

  @SuppressWarnings("deprecation")
  static final int PM_GET_SIGNATURES = PackageManager.GET_SIGNATURES;

  // to show progress
  public static final int CREATE_PACKAGES_LIST = -1;
  public static final int REF_PERMS_LIST = -2;
  public static final int OP_TO_SWITCH_LIST = -3;
  public static final int OP_TO_DEF_MODE_LIST = -4;
  public static final int PERM_TO_OP_CODE_MAP = -5;

  // create singleton instance of PackageParser so that all activities can update mPackagesListLive
  // whenever needed
  public static synchronized PackageParser getInstance() {
    if (mPackageParser == null) {
      mPackageParser = new PackageParser();
      mPackageParser.initializeVariables();
    }
    return mPackageParser;
  }

  private PackageParser() {}

  private void initializeVariables() {
    mPackageManager = App.getContext().getPackageManager();
    mMySettings = MySettings.getInstance();
    mAppOpsParser = new AppOpsParser(mPackageManager);
    mPrivDaemonHandler = PrivDaemonHandler.getInstance();
    mPermGroupsMapping = new PermGroupsMapping();
  }

  @SuppressLint("PackageManagerGetSignatures")
  private PackageInfo getPackageInfo(String pkgName, boolean getPermissions) {
    int flags = PM_GET_SIGNATURES;
    if (getPermissions) flags = PackageManager.GET_PERMISSIONS | flags;
    try {
      return mPackageManager.getPackageInfo(pkgName, flags);
    } catch (NameNotFoundException e) {
      Log.e(TAG, e.toString());
      return null;
    }
  }

  @SuppressWarnings("deprecation")
  private Signature[] getPackageSignatures(PackageInfo packageInfo) {
    return packageInfo.signatures;
  }

  public LiveData<List<Package>> getPackagesListLive() {
    updatePackagesList(true); // update list on app (re)launch
    return mPackagesListLive;
  }

  public LiveData<Package> getChangedPackage() {
    return mChangedPackage;
  }

  public LiveData<Integer> getProgressMax() {
    return mProgressMax;
  }

  public LiveData<Integer> getProgressNow() {
    return mProgressNow;
  }

  public Package getPackage(int position) {
    if (position < 0 || position >= mPackagesList.size()) {
      Log.e("PackageParser", "getPackage(): bad position: " + position);
      return null;
    }
    return mPackagesList.get(position);
  }

  public int getPackagePosition(Package pkg) {
    int position = mPackagesList.indexOf(pkg);
    if (position == -1) {
      Log.e("PackageParser", "getPackagePosition(): bad Package provided");
      return -1;
    }
    return position;
  }

  public void removePackage(Package pkg) {
    if (mPackagesList.remove(pkg)) {
      submitLiveData(mPackagesList);
    } else {
      Log.e("PackageParser", "removePackage(): bad Package provided");
    }
  }

  public int getPackagesListSize() {
    return mPackagesList.size();
  }

  private long lastPackageManagerCall = 0;
  private long mUpdatePackageListRefId;
  private Future<?> updatePackagesFuture;

  public void updatePackagesList(boolean doRepeatUpdates) {
    if (mMySettings.DEBUG)
      Utils.debugLog("updatePackagesList", "doRepeatUpdates: " + doRepeatUpdates);
    long myId = mUpdatePackageListRefId = System.nanoTime(); // to handle concurrent calls

    /**
     * In case of multiple calls, cancel the previous call if waiting for execution. Concurrent
     * calls to {@link packageInfoList} (in getInstalledPackages() or Collections.sort) cause
     * errors. Use {@link myId} and {@link mUpdatePackageListRefId} to break the previous loop if
     * new call comes.
     */
    if (updatePackagesFuture != null && !updatePackagesFuture.isDone()) {
      if (mMySettings.DEBUG) Utils.debugLog("updatePackagesList", "Cancelling previous call");
      updatePackagesFuture.cancel(false);
    }
    updatePackagesFuture =
        Utils.updatePackagesExecutor(() -> updatePackagesListInBg(doRepeatUpdates, myId));
  }

  private void updatePackagesListInBg(boolean doRepeatUpdates, long myId) {
    long startTime = System.currentTimeMillis();

    // Don't trouble Android on every call.
    if (System.currentTimeMillis() - lastPackageManagerCall > 5000) {
      if (mMySettings.DEBUG) Utils.debugLog("updatePackagesListInBg", "Updating packages list");
      setProgress(CREATE_PACKAGES_LIST, true, false);

      packageInfoList =
          mPackageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS | PM_GET_SIGNATURES);
      lastPackageManagerCall = System.currentTimeMillis();

      packageInfoList.sort(
          (o1, o2) -> {
            String p1 = o1.applicationInfo.loadLabel(mPackageManager).toString().toUpperCase();
            String p2 = o2.applicationInfo.loadLabel(mPackageManager).toString().toUpperCase();
            return p1.compareTo(p2);
          });
    }

    /** if permissions database changes, manually call {@link #updatePermReferences()} */
    if (mPermRefList == null) {
      setProgress(REF_PERMS_LIST, true, false);
      buildPermRefList();
    }

    if (!mMySettings.excludeAppOpsPerms() && mMySettings.canReadAppOps()) {
      if (mOpToSwitchList == null) {
        setProgress(OP_TO_SWITCH_LIST, true, false);
        mOpToSwitchList = mAppOpsParser.buildOpToSwitchList();
      }
      if (mOpToDefModeList == null) {
        setProgress(OP_TO_DEF_MODE_LIST, true, false);
        mOpToDefModeList = mAppOpsParser.buildOpToDefaultModeList();
      }
      if (mPermToOpCodeMap == null) {
        setProgress(PERM_TO_OP_CODE_MAP, true, false);
        mPermToOpCodeMap = mAppOpsParser.buildPermissionToOpCodeMap();
      }
    }

    if (mMySettings.DEBUG)
      Utils.debugLog("updatePackagesListInBg", "Total packages count: " + packageInfoList.size());
    // set progress bar scale ASAP
    setProgress(packageInfoList.size(), true, false);

    // using global mPackagesList here might give wrong results in case of concurrent calls
    List<Package> packageList = new ArrayList<>();

    for (int i = 0; i < packageInfoList.size(); i++) {
      // handle concurrent calls
      if (myId != mUpdatePackageListRefId) {
        if (mMySettings.DEBUG)
          Utils.debugLog("updatePackagesListInBg", "Breaking loop, new call received");
        return;
      }

      setProgress(i, false, false);
      PackageInfo packageInfo = packageInfoList.get(i);
      if (mMySettings.DEBUG)
        Utils.debugLog("updatePackagesListInBg", "Updating package: " + packageInfo.packageName);

      Package pkg = new Package();
      if ((isPkgUpdated(packageInfo, pkg)) && !isFilteredOut(pkg)) {
        packageList.add(pkg);
      }

      if (mMySettings.mDoRepeatUpdates && doRepeatUpdates) {
        if (shouldUpdateLiveData()) submitLiveData(packageList);
      }
    }

    // finally update complete list and complete progress
    submitLiveData(packageList);
    setProgress(packageInfoList.size(), false, true);

    if (mMySettings.DEBUG)
      Utils.debugLog(
          "updatePackagesListInBg",
          "Total time: " + (System.currentTimeMillis() - startTime) + "ms");
  }

  private boolean isFilteredOut(Package pkg) {
    if (isFilteredOutNoPermPkg(pkg)) return true;
    return mMySettings.isSearching()
        && mMySettings.isDeepSearchEnabled()
        && pkg.getPermCount() == 0
        && pkg.getAppOpsCount() == 0;
  }

  //////////////////////////////////////////////////////////////////
  /////////////////////////// PROGRESS /////////////////////////////
  //////////////////////////////////////////////////////////////////

  private long mLastProgressTimeStamp = 0;

  private void setProgress(int value, boolean isMax, boolean isFinal) {
    if (mMySettings.DEBUG) Utils.debugLog("setProgress", "Value: " + value + ", isMax: " + isMax);

    if (isMax) {
      Utils.runInFg(() -> mProgressMax.setValue(value));
      return;
    }

    if (isFinal) {
      Utils.runInFg(() -> mProgressNow.setValue(value));
      return;
    }

    // set progress updates, but not too frequent
    if ((System.currentTimeMillis() - mLastProgressTimeStamp) > 50) {
      Utils.runInFg(() -> mProgressNow.setValue(value));
      mLastProgressTimeStamp = System.currentTimeMillis();
    }
  }

  private long packagesListUpdateTimeStamp = 0;

  // do not update RecyclerView too frequently
  private boolean shouldUpdateLiveData() {
    return (System.currentTimeMillis() - packagesListUpdateTimeStamp) > 100;
  }

  //////////////////////////////////////////////////////////////////
  /////////////////////////// PACKAGES /////////////////////////////
  //////////////////////////////////////////////////////////////////

  private boolean isPkgUpdated(PackageInfo packageInfo, Package pkg) {
    if (isFilteredOutPkgName(packageInfo.packageName)) return false;

    boolean isSystemApp = isSystemApp(packageInfo);
    if (isFilteredOutSystemPkg(isSystemApp)) return false;

    boolean isFrameworkApp = isFrameworkApp(packageInfo);
    if (isFilteredOutFrameworkPkg(isFrameworkApp)) return false;
    if (isFilteredOutUserPkg(isFrameworkApp, isSystemApp)) return false;

    ApplicationInfo appInfo = packageInfo.applicationInfo;

    boolean isEnabled = appInfo.enabled;
    if (isFilteredOutDisabledPkg(!isEnabled)) return false;

    if (isFilteredOutNoIconPkg(appInfo.icon == 0)) return false;

    if (mMySettings.DEBUG)
      Utils.debugLog("PackageParser", "isPkgUpdated(): building permissions list");
    List<Permission> permissionsList = getPermissionsList(packageInfo, pkg);
    Boolean pkgIsReferenced = true;
    for (Permission perm : permissionsList) {
      if (perm.isReferenced() != null && !perm.isReferenced()) {
        pkgIsReferenced = false;
        break;
      }
    }

    if (pkgIsReferenced) {
      for (Permission perm : permissionsList) {
        if (perm.isReferenced() == null && perm.isChangeable()) {
          pkgIsReferenced = null;
          break;
        }
      }
    }

    // icon loading is costly call
    Drawable icon = mPackageIconsList.get(packageInfo.packageName);
    if (icon == null) {
      icon = appInfo.loadIcon(mPackageManager);
      mPackageIconsList.put(packageInfo.packageName, icon);
    }

    pkg.updatePackage(
        appInfo.loadLabel(mPackageManager).toString(),
        packageInfo.packageName,
        permissionsList,
        isFrameworkApp,
        isSystemApp,
        isEnabled,
        icon,
        appInfo.uid,
        pkgIsReferenced);
    if (mMySettings.DEBUG) Utils.debugLog("PackageParser", "isPkgUpdated(): Package created");
    return true;
  }

  private boolean isSystemApp(PackageInfo packageInfo) {
    return (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
  }

  private boolean isFrameworkApp(PackageInfo packageInfo) {
    if (systemSignatures == null) {
      PackageInfo pkgInfo = getPackageInfo("android", false);
      if (pkgInfo != null) systemSignatures = Arrays.asList(getPackageSignatures(pkgInfo));
    }
    for (Signature signature : getPackageSignatures(packageInfo)) {
      if (systemSignatures.contains(signature)) return true;
    }
    return false;
  }

  // when calling from PackageActivity for existing package
  public void updatePackage(Package pkg) {
    if (mMySettings.DEBUG) Utils.debugLog("PackageParser", "updatePackage(): " + pkg.getLabel());
    PackageInfo packageInfo = getPackageInfo(pkg.getName(), true);

    // package uninstalled or disabled from MainActivity
    if (packageInfo == null || !(isPkgUpdated(packageInfo, pkg))) {
      removePackage(pkg);
      return;
    }

    // update packages list when a Package's or Permission's state is changed so that RecyclerView
    // is updated on return to MainActivity
    submitLiveData(mPackagesList);
    Utils.runInFg(() -> mChangedPackage.setValue(pkg));
  }

  private boolean isFilteredOutPkgName(String pkgName) {
    return mMySettings.isPkgExcluded(pkgName);
  }

  private boolean isFilteredOutSystemPkg(boolean isSystemPkg) {
    return mMySettings.excludeSystemApps() && isSystemPkg;
  }

  private boolean isFilteredOutFrameworkPkg(boolean isFrameworkPkg) {
    return mMySettings.excludeFrameworkApps() && isFrameworkPkg;
  }

  private boolean isFilteredOutUserPkg(boolean isFrameworkPkg, boolean isSystemPkg) {
    return mMySettings.excludeUserApps() && !isFrameworkPkg && !isSystemPkg;
  }

  private boolean isFilteredOutDisabledPkg(boolean isDisabledPkg) {
    return mMySettings.excludeDisabledApps() && isDisabledPkg;
  }

  private boolean isFilteredOutNoIconPkg(boolean isNoIconPkg) {
    return mMySettings.excludeNoIconApps() && isNoIconPkg;
  }

  private boolean isFilteredOutNoPermPkg(Package pkg) {
    return mMySettings.excludeNoPermissionsApps() && pkg.getTotalPermCount() == 0;
  }

  //////////////////////////////////////////////////////////////////
  ////////////////////////// PERMISSIONS ///////////////////////////
  //////////////////////////////////////////////////////////////////

  // Update changed package and permissions from PackageActivity.
  // Calls to Room database require background execution and are time taking too.
  public void updatePermReferences(String pkgName, String permName, String state) {
    mPermRefList.remove(pkgName + "_" + permName);
    if (state != null) mPermRefList.put(pkgName + "_" + permName, state);
  }

  public void buildPermRefList() {
    if (mMySettings.DEBUG) Utils.debugLog("updatePackagesListInBg", "buildPermRefList() called");
    mPermRefList = new HashMap<>();
    for (PermissionEntity entity : mMySettings.getPermDb().getAll()) {
      mPermRefList.put(entity.pkgName + "_" + entity.permName, entity.state);
    }
  }

  private List<Permission> getPermissionsList(PackageInfo packageInfo, Package pkg) {
    String[] requestedPermissions = packageInfo.requestedPermissions;
    pkg.setTotalPermCount(requestedPermissions == null ? 0 : requestedPermissions.length);

    List<Permission> permissionsList = new ArrayList<>();
    if (isFilteredOutNoPermPkg(pkg)) return permissionsList;

    Permission permission;
    int permCount = 0;
    int[] appOpsCount1 = new int[] {0, 0};
    List<Integer> processedAppOps = new ArrayList<>();

    if (requestedPermissions != null) {
      if (mMySettings.DEBUG)
        Utils.debugLog("PackageParser", "getPermissionsList(): Parsing permissions list");
      for (int count = 0; count < requestedPermissions.length; count++) {
        String perm = requestedPermissions[count];
        permission = createPermission(packageInfo, perm, count);
        if (isNotFilteredOut(permission)) {
          permissionsList.add(permission);
          permCount++;
        }

        // not set AppOps corresponding to manifest permission
        if (!mMySettings.excludeAppOpsPerms() && mMySettings.canReadAppOps()) {
          int[] appOpsCount =
              createPermsAppOpsNotSet(packageInfo, perm, permissionsList, processedAppOps);
          appOpsCount1[0] += appOpsCount[0];
          appOpsCount1[1] += appOpsCount[1];
        }
      }
    }

    if (mMySettings.DEBUG) Utils.debugLog("PackageParser", "getPermissionsList(): Parsing AppOps");

    int[] appOpsCount2 = new int[] {0, 0};
    int[] appOpsCount3 = new int[] {0, 0};
    if (!mMySettings.excludeAppOpsPerms() && mMySettings.canReadAppOps()) {
      if (mMySettings.DEBUG)
        Utils.debugLog(
            "PackageParser",
            "getPermissionsList(): Parsing AppOps not corresponding to any manifest permission");
      appOpsCount2 = createSetAppOps(packageInfo, permissionsList, processedAppOps);

      if (mMySettings.DEBUG)
        Utils.debugLog("PackageParser", "getPermissionsList(): Parsing extra AppOps");
      // irrelevant / extra AppOps, not set and not corresponding to any manifest permission
      List<Integer> ops1 = new ArrayList<>();
      for (String opName : mMySettings.getExtraAppOps()) {
        int op = mMySettings.getAppOpsList().indexOf(opName);
        if (!processedAppOps.contains(op)) ops1.add(op);
      }

      if (ops1.size() != 0) {
        int[] ops2 = new int[ops1.size()];
        for (int i = 0; i < ops1.size(); i++) {
          ops2[i] = ops1.get(i);
        }
        appOpsCount3 = createExtraAppOps(packageInfo, permissionsList, ops2);
      }
    }

    pkg.setPermCount(permCount);
    pkg.setTotalAppOpsCount(appOpsCount1[0] + appOpsCount2[0] + appOpsCount3[0]);
    pkg.setAppOpsCount(appOpsCount1[1] + appOpsCount2[1] + appOpsCount3[1]);

    if (mMySettings.DEBUG)
      Utils.debugLog(
          "PackageParser", "getPermissionsList(): Permissions count: " + permissionsList.size());

    return permissionsList;
  }

  private boolean isNotFilteredOut(Permission permission) {
    if (mMySettings.isSearching()
        && mMySettings.isDeepSearchEnabled()
        && !permission.contains(mMySettings.mQueryText)) return false;

    // always show extra AppOps except in search query
    if (permission.isExtraAppOp()) return true;

    if (mMySettings.isPermExcluded(permission.getName())) return false;
    if (mMySettings.excludeNotChangeablePerms() && !permission.isChangeable()) return false;
    if (mMySettings.excludeNotGrantedPerms() && !permission.isGranted()) return false;

    if (permission.isAppOps()) {
      return !mMySettings.excludeNotSetAppOps() || permission.isAppOpsSet();
    }

    if (mMySettings.excludePrivilegedPerms() && permission.isPrivileged()) return false;
    if (mMySettings.excludeSignaturePerms()
        && permission.getProtectionLevel().equals(Permission.PROTECTION_SIGNATURE)) return false;
    if (mMySettings.excludeDangerousPerms()
        && permission.getProtectionLevel().equals(Permission.PROTECTION_DANGEROUS)) return false;
    if (mMySettings.excludeNormalPerms()
        && permission.getProtectionLevel().equals(Permission.PROTECTION_NORMAL)) return false;
    return !mMySettings.excludeInvalidPermissions() || !permission.isProviderMissing();
  }

  //////////////////////////////////////////////////////////////////
  ////////////////////// MANIFEST PERMISSION ///////////////////////
  //////////////////////////////////////////////////////////////////

  private Permission createPermission(PackageInfo packageInfo, String perm, int count) {
    int[] requestedPermissionsFlags = packageInfo.requestedPermissionsFlags;
    String protection = "Unknown";
    boolean isPrivileged = false;
    boolean isDevelopment = false;
    boolean isManifestPermAppOps = false;
    boolean isSystemFixed = false;
    boolean providerMissing = false;
    CharSequence permDesc = null;

    boolean isGranted =
        (requestedPermissionsFlags[count] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;

    try {
      PermissionInfo permissionInfo = mPackageManager.getPermissionInfo(perm, 0);

      @SuppressWarnings("deprecation")
      int protectionLevel = permissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
      @SuppressWarnings("deprecation")
      int protectionFlags = permissionInfo.protectionLevel & ~PermissionInfo.PROTECTION_MASK_BASE;
      @SuppressWarnings("deprecation")
      int PROTECTION_SIGNATURE_OR_SYSTEM = PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM;

      if (protectionLevel == PermissionInfo.PROTECTION_NORMAL) {
        protection = Permission.PROTECTION_NORMAL;
      } else if (protectionLevel == PermissionInfo.PROTECTION_DANGEROUS) {
        protection = Permission.PROTECTION_DANGEROUS;
      } else if (protectionLevel == PermissionInfo.PROTECTION_SIGNATURE) {
        protection = Permission.PROTECTION_SIGNATURE;
      } else if (protectionLevel == PROTECTION_SIGNATURE_OR_SYSTEM) {
        protection = Permission.PROTECTION_SIGNATURE;
      } else {
        Log.e(TAG, "Protection level for " + permissionInfo.name + ": " + protectionLevel);
      }

      isPrivileged = (protectionFlags & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0;
      isDevelopment =
          protectionLevel == PermissionInfo.PROTECTION_SIGNATURE
              && (protectionFlags & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0;
      isManifestPermAppOps = (protectionFlags & PermissionInfo.PROTECTION_FLAG_APPOP) != 0;

      permDesc = permissionInfo.loadDescription(mPackageManager);
    } catch (NameNotFoundException ignored) {
      // permissions provider is not available e.g. Play Services
      providerMissing = true;
    }

    String permState = isGranted ? Permission.GRANTED : Permission.REVOKED;
    RefPair refPair = getReference(packageInfo.packageName, perm, permState);
    GroupOrderPair groupOrderPair = mPermGroupsMapping.getOrderAndGroup(perm, false);

    boolean isSystemApp = isSystemApp(packageInfo);
    if (isSystemApp || isFrameworkApp(packageInfo)) {
      int permFlags = getPermissionFlags(perm, packageInfo.packageName);
      int systemFixedFlag = getSystemFixedFlag();
      if (permFlags >= 0 && systemFixedFlag >= 0) {
        isSystemFixed = (permFlags & systemFixedFlag) != 0;
      }
    }

    return new Permission(
        groupOrderPair.order,
        getIconResId(perm, groupOrderPair.group),
        false,
        false,
        false,
        -1,
        -1,
        null,
        false,
        packageInfo.packageName,
        perm,
        isGranted,
        protection,
        isPrivileged,
        isDevelopment,
        isManifestPermAppOps,
        isSystemFixed,
        providerMissing,
        refPair.isReferenced,
        refPair.reference,
        isSystemApp,
        permDesc);
  }

  private int getPermissionFlags(String perm, String pkg) {
    if (!mMySettings.mPrivDaemonAlive) {
      Utils.logDaemonDead(TAG + ": getPermissionFlags");
      return -1;
    } else {
      int userId = Process.myUid() / 100000;
      String command = PrivDaemon.GET_PERMISSION_FLAGS + " " + perm + " " + pkg + " " + userId;
      Object object = mPrivDaemonHandler.sendRequest(command);
      if (object instanceof Integer) {
        return (int) object;
      }
    }
    Log.e(TAG, "Error occurred in getPermissionFlags()");
    return -1;
  }

  private int systemFixedFlag = -1;

  private int getSystemFixedFlag() {
    if (systemFixedFlag != -1) return systemFixedFlag;

    if (mMySettings.useHiddenAPIs()) {
      // hidden API
      int flag = Utils.getIntField("FLAG_PERMISSION_SYSTEM_FIXED", PackageManager.class, TAG);
      if (flag != Utils.INT_FIELD_ERROR) {
        systemFixedFlag = flag;
        return systemFixedFlag;
      }
      Utils.hiddenAPIsNotWorking(TAG, "Could not get FLAG_PERMISSION_SYSTEM_FIXED field");
    } else if (!mMySettings.mPrivDaemonAlive) {
      Utils.logDaemonDead(TAG + ": getSystemFixedFlag");
      return -1;
    } else {
      Object object = mPrivDaemonHandler.sendRequest(PrivDaemon.GET_SYSTEM_FIXED_FLAG);
      if (object instanceof Integer) {
        systemFixedFlag = (int) object;
        return systemFixedFlag;
      }
    }
    Log.e(TAG, "Error occurred in getSystemFixedFlag()");
    return -1;
  }

  //////////////////////////////////////////////////////////////////
  //////////////////////////// APP OPS /////////////////////////////
  //////////////////////////////////////////////////////////////////

  private int[] createPermsAppOpsNotSet(
      PackageInfo packageInfo,
      String perm,
      List<Permission> permissionsList,
      List<Integer> processedAppOps) {

    Integer mappedOp = mPermToOpCodeMap.get(perm);
    if (mappedOp == null) return new int[] {0, 0};
    int op = mappedOp;

    int uid = packageInfo.applicationInfo.uid;

    List<MyPackageOps> pkgOpsList =
        mAppOpsParser.getOpsForPackage(uid, packageInfo.packageName, op);

    // do not return changed (set) ops, they are handled separately
    if (pkgOpsList != null && pkgOpsList.size() == 0) {
      return createAppOp(packageInfo, op, -1, permissionsList, processedAppOps, false, false, -1);
    }

    return new int[] {0, 0};
  }

  private int[] createSetAppOps(
      PackageInfo packageInfo, List<Permission> permissionsList, List<Integer> processedAppOps) {
    return createAppOpsList(packageInfo, permissionsList, processedAppOps, null);
  }

  private int[] createExtraAppOps(
      PackageInfo packageInfo, List<Permission> permissionsList, int[] ops) {
    return createAppOpsList(packageInfo, permissionsList, null, ops);
  }

  private int[] createAppOpsList(
      PackageInfo packageInfo,
      List<Permission> permissionsList,
      List<Integer> processedAppOps,
      int[] ops) {

    List<MyPackageOps> pkgOpsList = new ArrayList<>();
    int totalAppOpsCount = 0;
    int appOpsCount = 0;
    boolean isExtraAppOp = ops != null;
    int uid = packageInfo.applicationInfo.uid;

    List<MyPackageOps> list;
    if (isExtraAppOp) {
      for (int op : ops) {
        list = mAppOpsParser.getOpsForPackage(uid, packageInfo.packageName, op);
        if (list != null) {
          if (list.size() == 0) {
            int[] count =
                createAppOp(packageInfo, op, -1, permissionsList, processedAppOps, true, false, -1);
            totalAppOpsCount += count[0];
            appOpsCount += count[1];
          } else {
            pkgOpsList.addAll(list);
          }
        }
      }
    } else {
      list = mAppOpsParser.getOpsForPackage(uid, packageInfo.packageName, null);
      if (list != null) pkgOpsList.addAll(list);

      // UID mode: android-10.0.0_r1: AppOpsService.java#3378
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        list = mAppOpsParser.getUidOps(uid);
        if (list != null) pkgOpsList.addAll(list);
      }
    }

    for (MyPackageOps myPackageOps : pkgOpsList) {
      boolean isPerUid = myPackageOps.getPackageName() == null;
      for (MyPackageOps.MyOpEntry myOpEntry : myPackageOps.getOps()) {
        int op = myOpEntry.getOp();
        long lastAccessTime = myOpEntry.getLastAccessTime();
        int[] count =
            createAppOp(
                packageInfo,
                op,
                myOpEntry.getMode(),
                permissionsList,
                processedAppOps,
                isExtraAppOp,
                isPerUid,
                lastAccessTime);
        totalAppOpsCount += count[0];
        appOpsCount += count[1];
      }
    }
    return new int[] {totalAppOpsCount, appOpsCount};
  }

  private int[] createAppOp(
      PackageInfo packageInfo,
      int op,
      int opMode,
      List<Permission> permissionsList,
      List<Integer> processedAppOps,
      boolean isExtraAppOp,
      boolean isPerUid,
      long accessTime) {
    int opSwitch = mOpToSwitchList.get(op);
    String dependsOn = op == opSwitch ? null : mMySettings.getAppOpsList().get(opSwitch);
    String opName = mMySettings.getAppOpsList().get(op);
    boolean isAppOpSet = true;
    if (opMode < 0) {
      isAppOpSet = false;
      opMode = mOpToDefModeList.get(op);
    }
    String opState = mMySettings.getAppOpsModes().get(opMode);
    RefPair refPair = getReference(packageInfo.packageName, opName, opState);
    GroupOrderPair groupOrderPair = mPermGroupsMapping.getOrderAndGroup(opName, true);

    Permission permission =
        new Permission(
            groupOrderPair.order,
            getIconResId(opName, groupOrderPair.group),
            true,
            isPerUid,
            isAppOpSet,
            opMode,
            accessTime,
            dependsOn,
            isExtraAppOp,
            packageInfo.packageName,
            opName,
            opMode != AppOpsManager.MODE_IGNORED && opMode != AppOpsManager.MODE_ERRORED,
            "AppOps",
            false,
            false,
            false,
            false,
            false,
            refPair.isReferenced,
            refPair.reference,
            isSystemApp(packageInfo),
            null);

    // so that it's not repeated
    if (!isExtraAppOp) processedAppOps.add(op);

    int appOpsCount = 0;

    if (isNotFilteredOut(permission)) {
      permissionsList.add(permission);
      appOpsCount = 1;
    } else if (!isExtraAppOp && mMySettings.isExtraAppOp(permission.getName())) {
      permission.setExtraAppOp();
      if (isNotFilteredOut(permission)) {
        permissionsList.add(permission);
        appOpsCount = 1;
      }
    }

    return new int[] {1, appOpsCount};
  }

  //////////////////////////////////////////////////////////////////
  ///////////////////////// HELPER METHODS /////////////////////////
  //////////////////////////////////////////////////////////////////

  private int getIconResId(String perm, String group) {
    Integer iconResId = mPermIconsResIds.get(perm);
    if (iconResId == null) {
      iconResId = Utils.getIntField("g_" + group.toLowerCase(), R.drawable.class, TAG);
      mPermIconsResIds.put(perm, iconResId);
    }
    return iconResId;
  }

  private RefPair getReference(String pkgName, String permName, String state) {
    String refState = mPermRefList.get(pkgName + "_" + permName);
    if (refState == null) return new RefPair(); // both values null
    RefPair refPair = new RefPair();
    refPair.isReferenced = state.equals(refState);
    refPair.reference = refState;
    return refPair;
  }

  private static class RefPair {
    Boolean isReferenced;
    String reference;
  }

  public List<String> buildAppOpsList() {
    return mAppOpsParser.buildAppOpsList();
  }

  public List<String> buildAppOpsModes() {
    return mAppOpsParser.buildAppOpsModes();
  }

  //////////////////////////////////////////////////////////////////
  ///////////////////////////// SEARCH /////////////////////////////
  //////////////////////////////////////////////////////////////////

  private void submitLiveData(List<Package> packagesList) {
    mPackagesList = packagesList;
    if (mMySettings.isDeepSearchEnabled()) {
      Utils.runInFg(() -> mPackagesListLive.setValue(mPackagesList));
      if (mMySettings.DEBUG)
        Utils.debugLog(
            "submitLiveData",
            "Shallow search disabled, posting " + mPackagesList.size() + " packages");
      return;
    }
    if (mMySettings.DEBUG) Utils.debugLog("submitLiveData", "Doing shallow search");
    handleSearchQuery(false);
  }

  private long mHandleSearchQueryRefId;
  private Future<?> searchQueryFuture;

  public void handleSearchQuery(boolean doRepeatUpdates) {
    if (!mMySettings.isSearching()) {
      Utils.runInFg(() -> mPackagesListLive.setValue(mPackagesList));
      if (mMySettings.DEBUG)
        Utils.debugLog(
            "handleSearchQuery", "Empty query text, posting " + mPackagesList.size() + " packages");
      return;
    }

    long myId = mHandleSearchQueryRefId = System.nanoTime();
    if (searchQueryFuture != null && !searchQueryFuture.isDone()) {
      if (mMySettings.DEBUG) Utils.debugLog("handleSearchQuery", "Cancelling previous call");
      searchQueryFuture.cancel(false);
    }
    searchQueryFuture = Utils.searchQueryExecutor(() -> doSearchInBg(doRepeatUpdates, myId));
  }

  private void doSearchInBg(boolean doRepeatUpdates, long myId) {
    String queryText = mMySettings.mQueryText;
    List<Package> packageList = new ArrayList<>();

    for (Package pkg : new ArrayList<>(mPackagesList)) {
      if (myId != mHandleSearchQueryRefId) {
        if (mMySettings.DEBUG) Utils.debugLog("doSearchInBg", "Breaking loop, new call received");
        return;
      }
      if (pkg.contains(queryText)) packageList.add(pkg);
      if (doRepeatUpdates && shouldUpdateLiveData()) {
        postLiveData(packageList);
        packagesListUpdateTimeStamp = System.currentTimeMillis();
      }
    }
    postLiveData(packageList);
  }

  private void postLiveData(List<Package> packageList) {
    if (mMySettings.DEBUG)
      Utils.debugLog("handleSearchQuery", "Posting " + packageList.size() + " packages");
    Utils.runInFg(() -> mPackagesListLive.setValue(packageList));
  }
}