package com.twofours.surespot.backup;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.ViewSwitcher;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.common.AccountPicker;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.ChildList;
import com.google.api.services.drive.model.ChildReference;
import com.google.api.services.drive.model.FileList;
import com.twofours.surespot.R;
import com.twofours.surespot.activities.MainActivity;
import com.twofours.surespot.common.FileUtils;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.identity.IdentityController;
import com.twofours.surespot.identity.IdentityOperationResult;
import com.twofours.surespot.network.IAsyncCallback;
import com.twofours.surespot.ui.SingleProgressDialog;
import com.twofours.surespot.ui.UIUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ImportIdentityActivity extends Activity {
    private static final String TAG = "ImportIdentityActivity";
    private boolean mSignup;

    private TextView mAccountNameDisplay;
    private boolean mShowingLocal;
    private DriveHelper mDriveHelper;
    private ListView mDriveListview;
    private SingleProgressDialog mSpd;
    private SingleProgressDialog mSpdLoadIdentities;
    public static final String[] ACCOUNT_TYPE = new String[]{GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE};
    private static final String ACTION_DRIVE_OPEN = "com.google.android.apps.drive.DRIVE_OPEN";
    private static final String EXTRA_FILE_ID = "resourceId";
    private String mFileId;
    private int mMode;
    private static final int MODE_NORMAL = 0;
    private static final int MODE_DRIVE = 1;
    private ViewSwitcher mSwitcher;
    private SimpleAdapter mDriveAdapter;
    private AlertDialog mDialog;
    private boolean mChooseAccountDialogShowing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UIUtils.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_identity);
        Utils.configureActionBar(this, getString(R.string.identity), getString(R.string.restore), true);

        Intent intent = getIntent();

        Utils.logIntent(TAG, intent);
        mSignup = intent.getBooleanExtra("signup", false);
        mSpdLoadIdentities = new SingleProgressDialog(ImportIdentityActivity.this, getString(R.string.progress_loading_identities), 0);

        final String action = intent.getAction();

        // Make sure the Action is DRIVE_OPEN.
        if (ACTION_DRIVE_OPEN.equals(action)) {
            // Get the Drive file ID.
            mFileId = intent.getStringExtra(EXTRA_FILE_ID);
            mMode = MODE_DRIVE;
        }
        else {
            mMode = MODE_NORMAL;

        }

        mDriveHelper = new DriveHelper(getApplicationContext(), mMode == MODE_NORMAL);

        Account account = mDriveHelper.getDriveAccount();
        mAccountNameDisplay = (TextView) findViewById(R.id.restoreDriveAccount);
        mAccountNameDisplay.setText(account == null ? getString(R.string.no_google_account_selected) : account.name);

        Button chooseAccountButton = (Button) findViewById(R.id.bSelectDriveAccount);
        chooseAccountButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                chooseAccount(true);
            }
        });

        mDriveListview = (ListView) findViewById(R.id.lvDriveIdentities);
        mDriveListview.setEmptyView(findViewById(R.id.no_drive_identities));

        mDriveListview.setOnItemClickListener(new OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> parent, View view, final int position, long id) {
                if (IdentityController.getIdentityCount(ImportIdentityActivity.this) >= SurespotConstants.MAX_IDENTITIES) {
                    Utils.makeLongToast(ImportIdentityActivity.this, getString(R.string.login_max_identities_reached, SurespotConstants.MAX_IDENTITIES));
                    return;
                }

                @SuppressWarnings("unchecked")
                final Map<String, String> map = (Map<String, String>) mDriveAdapter.getItem(position);

                final String user = map.get("name");

                // make sure file we're going to save to is writable
                // before we
                // start
                if (!IdentityController.ensureIdentityFile(ImportIdentityActivity.this, user, true)) {
                    Utils.makeToast(ImportIdentityActivity.this, getString(R.string.could_not_restore_identity_name, user));
                    if (mMode == MODE_DRIVE) {
                        finish();
                    }
                    return;
                }

                mDialog = UIUtils.passwordDialog(ImportIdentityActivity.this, getString(R.string.restore_identity, user),
                        getString(R.string.enter_password_for, user), new IAsyncCallback<String>() {
                            @Override
                            public void handleResponse(final String password) {
                                if (!TextUtils.isEmpty(password)) {
                                    if (mSpd == null) {
                                        mSpd = new SingleProgressDialog(ImportIdentityActivity.this, getString(R.string.progress_restoring_identity), 0);
                                    }
                                    mSpd.show();

                                    final String url = map.get("url");

                                    new AsyncTask<Void, Void, Void>() {

                                        @Override
                                        protected Void doInBackground(Void... params) {
                                            byte[] identityBytes = mDriveHelper.getFileContent(url);
                                            identityBytes = FileUtils.gunzipIfNecessary(identityBytes);

                                            IdentityController.importIdentityBytes(ImportIdentityActivity.this, user, password, identityBytes,
                                                    new IAsyncCallback<IdentityOperationResult>() {

                                                        @Override
                                                        public void handleResponse(final IdentityOperationResult response) {
                                                            Utils.clearIntent(getIntent());
                                                            mSpd.hide();
                                                            ImportIdentityActivity.this.runOnUiThread(new Runnable() {

                                                                @Override
                                                                public void run() {

                                                                    Utils.makeLongToast(ImportIdentityActivity.this, response.getResultText());

                                                                    if (response.getResultSuccess()) {
                                                                        // if
                                                                        // launched
                                                                        // from
                                                                        // signup
                                                                        // and
                                                                        // successful
                                                                        // import,
                                                                        // go
                                                                        // to
                                                                        // login
                                                                        // screen
                                                                        if (mSignup || mMode == MODE_DRIVE) {

                                                                            IdentityController.logout(ImportIdentityActivity.this);

                                                                            Intent intent = new Intent(ImportIdentityActivity.this, MainActivity.class);
                                                                            intent.putExtra(SurespotConstants.ExtraNames.MESSAGE_TO, user);
                                                                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                                                            Utils.putUserSharedPrefsString(ImportIdentityActivity.this, user, SurespotConstants.ExtraNames.JUST_RESTORED_IDENTITY, "true");
                                                                            startActivity(intent);
                                                                            finish();
                                                                        }
                                                                    }

                                                                }
                                                            });

                                                        }

                                                    });
                                            return null;
                                        }

                                    }.execute();

                                }
                                else {
                                    Utils.makeToast(ImportIdentityActivity.this, getString(R.string.no_identity_imported));
                                }
                            }
                        });

            }

        });

        mSwitcher = (ViewSwitcher) findViewById(R.id.restoreViewSwitcher);
        final RadioButton rbRestoreLocal = (RadioButton) findViewById(R.id.rbRestoreLocal);
        final RadioButton rbRestoreDrive = (RadioButton) findViewById(R.id.rbRestoreDrive);
        if (mMode == MODE_NORMAL) {

            rbRestoreLocal.setTag("local");
            rbRestoreLocal.setChecked(true);
            mShowingLocal = true;

            rbRestoreDrive.setTag("drive");


            OnClickListener rbClickListener = new OnClickListener() {

                @Override
                public void onClick(View view) {
                    //if we're showing choose accounts do nothing
                    if (mChooseAccountDialogShowing) {
                        SurespotLog.d(TAG, "choose account dialog showing, not doing anything");
                        //select previous value
                        if (rbRestoreLocal.isChecked()) {
                            rbRestoreDrive.setChecked(true);
                        }
                        else {
                            rbRestoreLocal.setChecked(true);
                        }
                        return;
                    }


                    // Is the button now checked?
                    boolean checked = ((RadioButton) view).isChecked();


                    if (checked) {
                        if (view.getTag().equals("drive")) {
                            if (mShowingLocal) {

                                mDriveListview.setAdapter(null);
                                mSwitcher.showNext();
                                mShowingLocal = false;

                                if (mMode == MODE_NORMAL) {
                                    if (mDriveHelper.getDriveAccount() != null) {
                                        Drive drive = mDriveHelper.getDriveService();
                                        if (drive != null) {
                                            mSpdLoadIdentities.show();
                                            new AsyncTask<Void, Void, Void>() {
                                                @Override
                                                protected Void doInBackground(Void... params) {
                                                    populateDriveIdentities(true);

                                                    return null;
                                                }

                                            }.execute();
                                        }
                                    }
                                    else {
                                        chooseAccount(false);
                                    }
                                }

                            }
                        }
                        else {
                            if (!mShowingLocal) {
                                mSwitcher.showPrevious();
                                mShowingLocal = true;
                                checkPermissionReadStorage(ImportIdentityActivity.this);
                            }
                        }

                    }
                }
            };

            rbRestoreDrive.setOnClickListener(rbClickListener);
            rbRestoreLocal.setOnClickListener(rbClickListener);
            checkPermissionReadStorage(this);


        }
        else {
            rbRestoreLocal.setVisibility(View.GONE);
            rbRestoreDrive.setChecked(true);
            mSwitcher.showNext();
            mShowingLocal = false;

            new AsyncTask<Void, Void, Void>() {

                @Override
                protected Void doInBackground(Void... params) {
                    restoreExternal(true);
                    return null;
                }
            }.execute();

        }

    }

    private void setupLocal() {

        ListView lvIdentities = (ListView) findViewById(R.id.lvLocalIdentities);
        lvIdentities.setEmptyView(findViewById(R.id.no_local_identities));

        List<HashMap<String, String>> items = new ArrayList<HashMap<String, String>>();

        // query the filesystem for identities
        final File exportDir = FileUtils.getIdentityExportDir();
        File[] files = IdentityController.getExportIdentityFiles(this, exportDir.getPath());

        TextView tvLocalLocation = (TextView) findViewById(R.id.restoreLocalLocation);

        if (files != null) {
            TreeMap<Long, File> sortedFiles = new TreeMap<Long, File>(new Comparator<Long>() {
                public int compare(Long o1, Long o2) {
                    return o2.compareTo(o1);
                }
            });

            for (File file : files) {
                sortedFiles.put(file.lastModified(), file);
            }

            for (File file : sortedFiles.values()) {
                long lastModTime = file.lastModified();
                String date = DateFormat.getDateFormat(this).format(lastModTime) + " " + DateFormat.getTimeFormat(this).format(lastModTime);

                HashMap<String, String> map = new HashMap<String, String>();
                map.put("name", IdentityController.getIdentityNameFromFile(file));
                map.put("date", date);
                items.add(map);
            }
        }

        final SimpleAdapter adapter = new SimpleAdapter(this, items, R.layout.identity_item, new String[]{"name", "date"}, new int[]{
                R.id.identityBackupName, R.id.identityBackupDate});
        tvLocalLocation.setText(exportDir.toString());
        lvIdentities.setVisibility(View.VISIBLE);

        lvIdentities.setAdapter(adapter);
        lvIdentities.setOnItemClickListener(new OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (IdentityController.getIdentityCount(ImportIdentityActivity.this) >= SurespotConstants.MAX_IDENTITIES) {
                    Utils.makeLongToast(ImportIdentityActivity.this, getString(R.string.login_max_identities_reached, SurespotConstants.MAX_IDENTITIES));
                    return;
                }

                @SuppressWarnings("unchecked")
                Map<String, String> map = (Map<String, String>) adapter.getItem(position);

                final String user = map.get("name");

                // make sure file we're going to save to is writable before we
                // start
                if (!IdentityController.ensureIdentityFile(ImportIdentityActivity.this, user, true)) {
                    Utils.makeToast(ImportIdentityActivity.this, getString(R.string.could_not_import_identity));
                    if (mMode == MODE_DRIVE) {
                        finish();
                    }
                    return;
                }

                UIUtils.passwordDialog(ImportIdentityActivity.this, getString(R.string.restore_identity, user), getString(R.string.enter_password_for, user),
                        new IAsyncCallback<String>() {
                            @Override
                            public void handleResponse(String result) {

                                if (!TextUtils.isEmpty(result)) {
                                    if (mSpd == null) {
                                        mSpd = new SingleProgressDialog(ImportIdentityActivity.this, getString(R.string.progress_restoring_identity), 0);
                                    }
                                    mSpd.show();

                                    IdentityController.importIdentity(ImportIdentityActivity.this, exportDir, user, result,
                                            new IAsyncCallback<IdentityOperationResult>() {

                                                @Override
                                                public void handleResponse(final IdentityOperationResult response) {
                                                    Runnable runnable = new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            mSpd.hide();
                                                            Utils.makeLongToast(ImportIdentityActivity.this, response.getResultText());

                                                            if (response.getResultSuccess()) {
                                                                // if launched
                                                                // from
                                                                // signup and
                                                                // successful
                                                                // import, go to
                                                                // login
                                                                // screen
                                                                Utils.putUserSharedPrefsString(ImportIdentityActivity.this, user, SurespotConstants.ExtraNames.JUST_RESTORED_IDENTITY, "true");

                                                                if (mSignup) {
                                                                    IdentityController.logout(ImportIdentityActivity.this);

                                                                    Intent intent = new Intent(ImportIdentityActivity.this, MainActivity.class);
                                                                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                                                    startActivity(intent);
                                                                }
                                                            }
                                                        }
                                                    };

                                                    ImportIdentityActivity.this.runOnUiThread(runnable);
                                                }
                                            });
                                }
                                else {
                                    Utils.makeToast(ImportIdentityActivity.this, getString(R.string.no_identity_imported));
                                }
                            }
                        });
            }
        });
    }

    public void checkPermissionReadStorage(Activity activity) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, SurespotConstants.IntentRequestCodes.READ_EXTERNAL_STORAGE);
        }
        else {
            setupLocal();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case SurespotConstants.IntentRequestCodes.READ_EXTERNAL_STORAGE: {
                //premission to read storage
                if (grantResults.length > 0) {
                    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        setupLocal();
                    }
                    else if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                            Utils.makeLongToast(this, getString(R.string.need_storage_permission));
                        }
                        else {
                            //didn't want to give us permission
                            Utils.makeLongToast(this, getString(R.string.enable_storage_permission));
                        }
                    }
                }
            }
        }
    }

    private void restoreExternal(boolean firstTime) {
        if (!firstTime) {

            this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    Utils.makeToast(ImportIdentityActivity.this, getString(R.string.could_not_import_identity));
                    finish();
                    return;
                }
            });
        }

        if (mDriveHelper.getDriveAccount() == null) {
            chooseAccount(false);
            return;
        }

        List<HashMap<String, String>> items = new ArrayList<HashMap<String, String>>();
        try {
            com.google.api.services.drive.model.File file = mDriveHelper.getDriveService().files().get(mFileId).execute();

            if (!file.getLabels().getTrashed()) {

                DateTime lastModTime = file.getModifiedDate();

                String date = DateFormat.getDateFormat(this).format(lastModTime.getValue()) + " "
                        + DateFormat.getTimeFormat(this).format(lastModTime.getValue());
                HashMap<String, String> map = new HashMap<String, String>();
                String name = IdentityController.getIdentityNameFromFilename(file.getTitle());
                map.put("name", name);
                map.put("date", date);
                map.put("url", file.getDownloadUrl());
                items.add(map);
            }
            else {
                SurespotLog.w(TAG, "could not retrieve identity from google drive");
                this.runOnUiThread(new Runnable() {

                    @Override
                    public void run() {
                        Utils.makeToast(ImportIdentityActivity.this, getString(R.string.could_not_import_identity));
                    }
                });
                finish();

                return;
            }

        }
        catch (UserRecoverableAuthIOException e) {
            try {
                startActivityForResult(e.getIntent(), SurespotConstants.IntentRequestCodes.REQUEST_GOOGLE_AUTH);
            }
            catch (NullPointerException npe) {
            }
            return;

        }
        catch (GoogleJsonResponseException e) {
            SurespotLog.w(TAG, e, "could not retrieve identity from google drive");

            // if they're restoring from drive, selecting different account in
            // surespot will cause 404
            if (e.getStatusCode() == 404 && mMode == MODE_DRIVE) {
                this.runOnUiThread(new Runnable() {

                    @Override
                    public void run() {
                        Utils.makeLongToast(ImportIdentityActivity.this, getString(R.string.could_not_import_identity_drive_404));
                    }
                });

            }
        }
        catch (IOException e) {
            SurespotLog.w(TAG, e, "could not retrieve identity from google drive");

            this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    Utils.makeToast(ImportIdentityActivity.this, getString(R.string.could_not_import_identity));
                }
            });

            finish();
            return;

        }
        catch (SecurityException e) {
            SurespotLog.w(TAG, e, "createDriveIdentityDirectory");
            // when key is revoked on server this happens...should return
            // userrecoverable it seems
            // was trying to figure out how to test this
            // seems like the only way around this is to remove and re-add
            // android account:
            // http://stackoverflow.com/questions/5805657/revoke-account-permission-for-an-app
            this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    Utils.makeLongToast(ImportIdentityActivity.this, getString(R.string.re_add_google_account));

                }
            });

            finish();
            return;
        }

        SurespotLog.v(TAG, "loaded %d identities from google drive", items.size());

        mDriveAdapter = new SimpleAdapter(this, items, R.layout.identity_item, new String[]{"name", "date"}, new int[]{R.id.identityBackupName,
                R.id.identityBackupDate});

        this.runOnUiThread(new Runnable() {

            @Override
            public void run() {

                mDriveListview.setAdapter(mDriveAdapter);

            }
        });

    }

    private void populateDriveIdentities(boolean firstAttempt) {

        String identityDirId = ensureDriveIdentityDirectory();
        if (identityDirId == null) {
            if (!firstAttempt) {

                this.runOnUiThread(new Runnable() {

                    @Override
                    public void run() {
                        Utils.makeToast(ImportIdentityActivity.this, getString(R.string.could_not_list_identities_from_google_drive));
                    }
                });
            }
            return;
        }

        List<HashMap<String, String>> items = new ArrayList<HashMap<String, String>>();
        try {
            // query the drive for identities
            ChildList fileList = getIdentityFiles(identityDirId);
            if (fileList == null) {
                SurespotLog.v(TAG, "no identity backup files found on google drive");
                this.runOnUiThread(new Runnable() {

                    @Override
                    public void run() {
                        mSpdLoadIdentities.hide();
                    }
                });
                return;

            }

            List<ChildReference> refs = fileList.getItems();

            if (refs.size() == 0) {
                SurespotLog.v(TAG, "no identity backup files found on google drive");
                this.runOnUiThread(new Runnable() {

                    @Override
                    public void run() {
                        mSpdLoadIdentities.hide();
                    }
                });
                return;
            }

            if (refs.size() > 0) {
                TreeMap<Long, com.google.api.services.drive.model.File> sortedFiles = new TreeMap<Long, com.google.api.services.drive.model.File>(
                        new Comparator<Long>() {
                            public int compare(Long o1, Long o2) {
                                return o2.compareTo(o1);
                            }
                        });
                for (ChildReference ref : refs) {
                    com.google.api.services.drive.model.File file = mDriveHelper.getDriveService().files().get(ref.getId()).execute();

                    if (!file.getLabels().getTrashed()) {
                        DateTime lastModTime = file.getModifiedDate();
                        sortedFiles.put(lastModTime.getValue(), file);
                    }
                }

                for (com.google.api.services.drive.model.File file : sortedFiles.values()) {
                    DateTime lastModTime = file.getModifiedDate();
                    String date = DateFormat.getDateFormat(this).format(lastModTime.getValue()) + " "
                            + DateFormat.getTimeFormat(this).format(lastModTime.getValue());
                    HashMap<String, String> map = new HashMap<String, String>();
                    String name = IdentityController.getIdentityNameFromFilename(file.getTitle());
                    map.put("name", name);
                    map.put("date", date);
                    map.put("url", file.getDownloadUrl());
                    items.add(map);
                }

            }
        }
        catch (UserRecoverableAuthIOException e) {
            try {
                startActivityForResult(e.getIntent(), SurespotConstants.IntentRequestCodes.REQUEST_GOOGLE_AUTH);
            }
            catch (NullPointerException npe) {
            }
            return;
        }
        catch (IOException e) {
            SurespotLog.w(TAG, e, "could not retrieve identities from google drive");
            this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    Utils.makeToast(ImportIdentityActivity.this, getString(R.string.could_not_list_identities_from_google_drive));
                }
            });

            return;

        }
        catch (SecurityException e) {
            SurespotLog.w(TAG, e, "createDriveIdentityDirectory");
            // when key is revoked on server this happens...should return
            // userrecoverable it seems
            // was trying to figure out how to test this
            // seems like the only way around this is to remove and re-add
            // android account:
            // http://stackoverflow.com/questions/5805657/revoke-account-permission-for-an-app
            this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    Utils.makeLongToast(ImportIdentityActivity.this, getString(R.string.re_add_google_account));

                }
            });

            return;
        }

        SurespotLog.v(TAG, "loaded %d identities from google drive", items.size());

        mDriveAdapter = new SimpleAdapter(this, items, R.layout.identity_item, new String[]{"name", "date"}, new int[]{R.id.identityBackupName,
                R.id.identityBackupDate});

        this.runOnUiThread(new Runnable() {

            @Override
            public void run() {

                mSpdLoadIdentities.hide();
                mDriveListview.setAdapter(mDriveAdapter);

            }
        });

    }

    private ChildList getIdentityFiles(String identityDirId) {
        ChildList identityFileList = null;
        try {
            identityFileList = mDriveHelper.getDriveService().children().list(identityDirId).execute();
        }
        catch (IOException e) {
            SurespotLog.w(TAG, e, "getIdentityFiles");
        }
        return identityFileList;
    }

    public String ensureDriveIdentityDirectory() {
        String identityDirId = null;
        try {
            // see if identities directory exists

            FileList identityDir = mDriveHelper.getDriveService().files().list()
                    .setQ("title = '" + SurespotConstants.DRIVE_IDENTITY_FOLDER + "' and trashed = false").execute();
            List<com.google.api.services.drive.model.File> items = identityDir.getItems();

            if (items.size() > 0) {
                for (com.google.api.services.drive.model.File file : items) {
                    if (!file.getLabels().getTrashed()) {
                        SurespotLog.d(TAG, "identity folder already exists");
                        identityDirId = file.getId();
                        break;
                    }
                }
            }
            if (identityDirId == null) {
                com.google.api.services.drive.model.File file = new com.google.api.services.drive.model.File();
                file.setTitle(SurespotConstants.DRIVE_IDENTITY_FOLDER);
                file.setMimeType(SurespotConstants.MimeTypes.DRIVE_FOLDER);

                com.google.api.services.drive.model.File insertedFile = mDriveHelper.getDriveService().files().insert(file).execute();

                identityDirId = insertedFile.getId();

            }

        }
        catch (UserRecoverableAuthIOException e) {
            SurespotLog.w(TAG, e, "createDriveIdentityDirectory");
            //try {
            startActivityForResult(e.getIntent(), SurespotConstants.IntentRequestCodes.REQUEST_GOOGLE_AUTH);
//			}
//			catch (NullPointerException npe) {
//				return null;
//			}
        }
        catch (IOException e) {
            SurespotLog.w(TAG, e, "createDriveIdentityDirectory");
        }
        catch (SecurityException e) {
            SurespotLog.e(TAG, e, "createDriveIdentityDirectory");
            // when key is revoked on server this happens...should return
            // userrecoverable it seems
            // was trying to figure out how to test this
            // seems like the only way around this is to remove and re-add
            // android account:
            // http://stackoverflow.com/questions/5805657/revoke-account-permission-for-an-app
            this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    Utils.makeLongToast(ImportIdentityActivity.this, getString(R.string.re_add_google_account));

                }
            });

        }

        return identityDirId;
    }

    // //////// DRIVE
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case SurespotConstants.IntentRequestCodes.CHOOSE_GOOGLE_ACCOUNT:
                mChooseAccountDialogShowing = false;
                if (resultCode == Activity.RESULT_OK && data != null) {

                    SurespotLog.w("Preferences", "SELECTED ACCOUNT WITH EXTRA: %s", data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME));
                    Bundle b = data.getExtras();

                    String accountName = b.getString(AccountManager.KEY_ACCOUNT_NAME);

                    SurespotLog.d("Preferences", "Selected account: " + accountName);
                    if (accountName != null && accountName.length() > 0) {

                        mDriveHelper.setDriveAccount(accountName);
                        mAccountNameDisplay.setText(accountName);
                        if (mDriveListview != null) {
                            mDriveListview.setAdapter(null);
                        }
                        if (mMode == MODE_NORMAL) {
                            mSpdLoadIdentities.show();
                        }
                        new AsyncTask<Void, Void, Void>() {
                            @Override
                            protected Void doInBackground(Void... params) {
                                if (mMode == MODE_NORMAL) {
                                    populateDriveIdentities(true);
                                }
                                else {
                                    restoreExternal(true);
                                }
                                return null;
                            }

                        }.execute();
                    }
                }
                break;

            case SurespotConstants.IntentRequestCodes.REQUEST_GOOGLE_AUTH:
                if (resultCode == Activity.RESULT_OK) {
                    SurespotLog.v(TAG, "onActivityResult OK");
                    if (mMode == MODE_NORMAL) {
                        mSpdLoadIdentities.show();
                    }
                    new AsyncTask<Void, Void, Boolean>() {

                        @Override
                        protected Boolean doInBackground(Void... params) {
                            Drive drive = mDriveHelper.getDriveService();
                            if (drive != null) {
                                if (mMode == MODE_NORMAL) {
                                    populateDriveIdentities(false);

                                }
                                else {
                                    restoreExternal(false);
                                }
                                return true;
                            }

                            return false;

                        }

                        protected void onPostExecute(Boolean result) {
                            if (!result) {
                                mSpdLoadIdentities.hide();
                            }
                        }
                    }.execute();

                }
                else {
                    SurespotLog.v(TAG, "onActivityResult not OK");
                    mSpdLoadIdentities.hide();
                }
        }
    }

    private void chooseAccount(boolean ask) {
        String descriptionText = null;
        if (mMode == MODE_DRIVE) {
            descriptionText = getString(R.string.pick_same_drive_account);
        }

        Intent accountPickerIntent = AccountPicker.newChooseAccountIntent(null, null, ACCOUNT_TYPE, ask || mMode == MODE_DRIVE, descriptionText, null, null,
                null);
        try {
            mChooseAccountDialogShowing = true;
            startActivityForResult(accountPickerIntent, SurespotConstants.IntentRequestCodes.CHOOSE_GOOGLE_ACCOUNT);
        }
        catch (ActivityNotFoundException e) {
            Utils.makeToast(ImportIdentityActivity.this, getString(R.string.device_does_not_support_google_drive));
            SurespotLog.i(TAG, e, "chooseAccount");
        }

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }
    }
}
