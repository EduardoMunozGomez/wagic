package org.libsdl.app;

import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Enumeration;

import android.os.StrictMode;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Scanner;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

import net.wagic.app.R;
import net.wagic.utils.StorageOptions;
import net.wagic.utils.DeckImporter;
import net.wagic.utils.ImgDownloader;
import android.app.Activity;
import android.app.AlertDialog;
import android.widget.ListView;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.VelocityTracker;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;

/**
 * SDL Activity
 */
public class SDLActivity extends Activity implements OnKeyListener {
    private static final String TAG = SDLActivity.class.getCanonicalName();

    //import deck globals
    public ArrayList<String> myresult = new ArrayList<String>();
    public String myclickedItem = "";

    // Main components
    private static SDLActivity mSingleton;
    private static SDLSurface mSurface;

    // Audio
    private static Thread mAudioThread;
    private static AudioTrack mAudioTrack;

    // Resource download
    public static final int DIALOG_DOWNLOAD_PROGRESS = 0;
    public static final int DIALOG_DOWNLOAD_ERROR = 1;
    private ProgressDialog mProgressDialog;
    private AlertDialog mErrorDialog;
    public String mErrorMessage = "";
    public Boolean mErrorHappened = false;
    public final static String RES_FOLDER = Environment.getExternalStorageDirectory().getPath() + "/Wagic/Res/";
    public static String RES_FILENAME = "core_0211.zip";
    public static String RES_URL = "https://github.com/Vitty85/wagic/releases/download/wagic-v0.21.1/core_0211.zip";

    public String systemFolder = Environment.getExternalStorageDirectory().getPath() + "/Wagic/Res/";
    private String userFolder = Environment.getExternalStorageDirectory().getPath() + "/Wagic/User/";

    // path to the onboard sd card that is not removable (typically /mnt/sdcard )
    private String internalPath = "";
    // path to removable sd card (on motorala devices /mnt/sdcard-ext, samsung devices: /mnt/sdcard/external_sd )
    private String sdcardPath = "";

    // Android only supports internal memory and internal sdcard. removable media is not currently accessible via API
    // using StorageOptions for now gives us a temporary interface to scan all available mounted drives.
    private Context mContext;

    // Preferences
    public static final String kWagicSharedPreferencesKey = "net.wagic.app.preferences.wagic";
    public static final String kStoreDataOnRemovableSdCardPreference = "StoreDataOnRemovableStorage";
    public static final String kSaveDataPathPreference = "StorageDataLocation";
    public static final String kWagicDataStorageOptionsKey = "dataStorageOptions";
    public static final int kStorageDataOptionsMenuId = 2000;
    public static final int kOtherOptionsMenuId = 3000;

    // Accessors
    public String getSystemStorageLocation() {
        return systemFolder;
    }

    public String getUserStorageLocation() {
        return userFolder;
    }

    // setters
    public void updateStorageLocations() {
        boolean usesInternalSdCard = (!getSharedPreferences(kWagicSharedPreferencesKey, MODE_PRIVATE).getBoolean(kStoreDataOnRemovableSdCardPreference, false)) && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());

        systemFolder = (usesInternalSdCard ? internalPath : sdcardPath) + "/Res/";
        userFolder = (usesInternalSdCard ? internalPath : sdcardPath) + "/User/";
    }

    /**
     * checks to see if the device has a memory card to write to that is in a valid state.
     *
     * @return true if the device can write to the sdcard, false if not.
     */
    public boolean checkStorageState() {
        SharedPreferences settings = getSharedPreferences(kWagicSharedPreferencesKey, MODE_PRIVATE);
        boolean mExternalStorageAvailable = false;
        boolean mExternalStorageWriteable = false;
        String state = Environment.getExternalStorageState();
        boolean useSdCard = (!settings.getBoolean(kStoreDataOnRemovableSdCardPreference, false)) && mExternalStorageWriteable;
        String systemStoragePath = getSystemStorageLocation();

        if (useSdCard && (systemStoragePath.indexOf(sdcardPath) != -1)) {
            Log.i(TAG, "Data will be written to sdcard.");
            return true;
        }

        if (!useSdCard && (systemStoragePath.indexOf(internalPath) != -1)) {
            Log.i(TAG, "Data will be written to internal storage.");
            return true;
        }

        if (Environment.MEDIA_MOUNTED.equals(state)) {
            // We can read and write the media
            mExternalStorageAvailable = mExternalStorageWriteable = true;
        } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            // We can only read the media
            mExternalStorageAvailable = true;
            mExternalStorageWriteable = false;
        } else {
            // Something else is wrong. It may be one of many other states, but all we need
            //  to know is we can neither read nor write
            mExternalStorageAvailable = mExternalStorageWriteable = false;
        }

        return (mExternalStorageAvailable && mExternalStorageWriteable);
    }

    private boolean getRemovableMediaStorageState() {
        for (String extMediaPath : StorageOptions.paths) {
            File mediaPath = new File(extMediaPath);
            if (mediaPath.canWrite())
                return true;
        }

        return false;
    }

    private void displayStorageOptions() {
        AlertDialog.Builder setStorage = new AlertDialog.Builder(this);
        setStorage.setTitle("Where would you like to store your data? On your removable SD Card or the built-in memory?");
        StorageOptions.determineStorageOptions();
        setStorage.setSingleChoiceItems(StorageOptions.labels, -1, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int item) {
                savePathPreference(item);
            }
        });

        setStorage.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                initStorage();
                if (mSurface == null)
                    mSingleton.initializeGame();
            }
        });

        setStorage.create().show();
    }

    private void importDeckOptions() {
        AlertDialog.Builder importDeck = new AlertDialog.Builder(this);
        importDeck.setTitle("Choose Deck to Import:");
        File root = new File(System.getenv("EXTERNAL_STORAGE") + "/Download");
        File[] files = root.listFiles();
        for (File f : files) {
            if (!myresult.contains(f.toString()) && (f.toString().contains(".txt") || f.toString().contains(".dck") || f.toString().contains(".dec")))
                myresult.add(f.toString());
        }

        //get first item?
        if (!myresult.isEmpty())
            myclickedItem = myresult.get(0).toString();

        importDeck.setSingleChoiceItems(myresult.toArray(new String[myresult.size()]), 0, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int item) {
                myclickedItem = myresult.get(item).toString();
            }
        });

        importDeck.setPositiveButton("Import Deck", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                processSelectedDeck(myclickedItem);
                if (mSurface == null)
                    mSingleton.initializeGame();
            }
        });

        importDeck.create().show();
    }

    private void processSelectedDeck(String mypath) {
        AlertDialog.Builder infoDialog = new AlertDialog.Builder(this);
        infoDialog.setTitle("Imported Deck:");
        String activePath = sdcardPath;
        if (activePath == "") {
            activePath = internalPath;
        }

        File f = new File(mypath);
        //Call the deck importer....
        String state = DeckImporter.importDeck(f, mypath, activePath);
        infoDialog.setMessage(state);
        infoDialog.show();
    }

    private void checkStorageLocationPreference() {
        SharedPreferences settings = getSharedPreferences(kWagicSharedPreferencesKey, MODE_PRIVATE);
        final SharedPreferences.Editor prefsEditor = settings.edit();
        boolean hasRemovableMediaMounted = getRemovableMediaStorageState();

        if (!settings.contains(kStoreDataOnRemovableSdCardPreference)) {
            if (hasRemovableMediaMounted) {
                displayStorageOptions();
            } else {
                prefsEditor.putBoolean(kStoreDataOnRemovableSdCardPreference, false);
                prefsEditor.commit();
                initStorage();
                mSingleton.initializeGame();
            }
        } else {
            boolean storeOnRemovableMedia = settings.getBoolean(kStoreDataOnRemovableSdCardPreference, false);
            if (storeOnRemovableMedia && !hasRemovableMediaMounted) {
                AlertDialog setStorage = new AlertDialog.Builder(this).create();
                setStorage.setTitle("Storage Preference");
                setStorage.setMessage("Removable Sd Card not detected.  Saving data to internal memory.");

                prefsEditor.putBoolean(kStoreDataOnRemovableSdCardPreference, false);
                prefsEditor.commit();

                initStorage();
                mSingleton.initializeGame();
                setStorage.show();
            } else {
                initStorage();
                mSingleton.initializeGame();
            }
        }
    }

    private void initStorage() {
        // check the state of the external storage to ensure we can even write to it.
        // we are going to assume that if an external location exists, and can be written to, use it.
        // Otherwise use internal storage
        try {
            //
            // initialize where all the files are going to be stored.
            //
            File wagicMediaPath = null;

            // String packageName = mContext.getPackageName(); // possibly use this to differentiate between different mods of Wagic.
            File externalFilesDir = Environment.getExternalStorageDirectory();
            if (externalFilesDir != null) {
                internalPath = externalFilesDir.getAbsolutePath() + "/Wagic";
            }

            String state = Environment.getExternalStorageState();
            if (Environment.MEDIA_MOUNTED.equals(state)) {
                wagicMediaPath = new File(internalPath);
                if (wagicMediaPath.canWrite())
                    wagicMediaPath.mkdirs();
            }

            // initialize the external mount
            SharedPreferences settings = getSharedPreferences(kWagicSharedPreferencesKey, MODE_PRIVATE);
            String selectedRemovableCardPath = settings.getString(kSaveDataPathPreference, internalPath);
            if (selectedRemovableCardPath != null && !internalPath.equalsIgnoreCase(selectedRemovableCardPath)) {
                wagicMediaPath = new File(selectedRemovableCardPath);
                if (!wagicMediaPath.exists() || !wagicMediaPath.canWrite()) {
                    Log.e(TAG, "Error in initializing system folder: " + selectedRemovableCardPath);
                } else { // found a removable media location
                    sdcardPath = selectedRemovableCardPath + "/Wagic";
                }
            }

            updateStorageLocations();
        } catch (Exception ioex) {
            Log.e(TAG, "An error occurred in setting up the storage locations.");
        }
    }

    private void savePathPreference(int selectedOption) {
        SharedPreferences settings = getSharedPreferences(kWagicSharedPreferencesKey, MODE_PRIVATE);
        String selectedMediaPath = StorageOptions.paths[selectedOption];
        final SharedPreferences.Editor prefsEditor = settings.edit();
        boolean saveToRemovableMedia = !"/mnt/sdcard".equalsIgnoreCase(selectedMediaPath);

        prefsEditor.putBoolean(kStoreDataOnRemovableSdCardPreference, saveToRemovableMedia);
        prefsEditor.putString(kSaveDataPathPreference, selectedMediaPath);
        prefsEditor.commit();

    }

    private void startDownload() {
        //String url = getResourceUrl();
        String url = RES_URL;
        if (!checkStorageState()) {
            Log.e(TAG, "Error in initializing storage space.");
            mSingleton.downloadError("Failed to initialize storage space for game. Please verify that your sdcard or internal memory is mounted properly.");
        }
        new DownloadFileAsync().execute(url);
    }

    public void downloadError(String errorMessage) {
        mErrorHappened = true;
        mErrorMessage = errorMessage;
    }

    private void buildStorageOptionsMenu(Menu menu) {
        StorageOptions.determineStorageOptions();
        for (int idx = 0; idx < StorageOptions.count; idx++) {
            menu.add(kStorageDataOptionsMenuId, kStorageDataOptionsMenuId + idx, idx, StorageOptions.labels[idx]);
        }
    }

    String set = "";
    String[] availableSets;
    ArrayList<String> selectedSets;
    boolean[] checkedSet;
    Integer totalset = 0;
    boolean finished = false;
    boolean loadResInProgress = false;
    ProgressDialog progressBarDialogRes;

    private void loadAvailableSets() {
        final Handler mHandler = new Handler();
        progressBarDialogRes = new ProgressDialog(this);
        progressBarDialogRes.setTitle("Loading all available sets...");
        progressBarDialogRes.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressBarDialogRes.setProgress(0);
        new Thread(new Runnable() {
            public void run() {
                ArrayList<String> sets = new ArrayList<String>();
                if (availableSets == null) {
                    loadResInProgress = true;
                    File baseFolder = new File(getSystemStorageLocation());
                    File[] listOfFiles = baseFolder.listFiles();
                    ZipFile zipFile = null;
                    try {
                        zipFile = new ZipFile(baseFolder + "/" + listOfFiles[0].getName());
                        Enumeration<? extends ZipEntry> e = zipFile.entries();
                        while (e.hasMoreElements()) {
                            ZipEntry entry = e.nextElement();
                            String entryName = entry.getName();
                            if (entryName != null && entryName.contains("sets/")) {
                                if (!entryName.equalsIgnoreCase("sets/") && !entryName.contains("primitives") && !entryName.contains(".")) {
                                    String[] names = entryName.split("/");
                                    sets.add(names[1]);
                                }
                            }
                        }
                    } catch (IOException ioe) {
                        System.out.println("Error opening zip file" + ioe);
                    } finally {
                        try {
                            if (zipFile != null) {
                                zipFile.close();
                            }
                        } catch (IOException ioe) {
                            System.out.println("Error while closing zip file" + ioe);
                        }
                    }

                    availableSets = new String[sets.size()];
                    checkedSet = new boolean[sets.size()];
                    progressBarDialogRes.setMax(sets.size());
                    for (int i = 0; i < availableSets.length; i++) {
                        availableSets[i] = sets.get(i) + " - " + ImgDownloader.getSetInfo(sets.get(i), true, getSystemStorageLocation());
                        checkedSet[i] = false;
                        progressBarDialogRes.incrementProgressBy((int) (1));
                    }
                }
                finished = true;
                loadResInProgress = false;
                progressBarDialogRes.dismiss();
                mHandler.post(new Runnable() {
                    public void run() {
                        while (!finished) {
                            try {
                                Thread.sleep(5000);
                            } catch (Exception e) {
                            }
                        }
                        downloadCardImages();
                    }
                });
            }
        }).start();

        progressBarDialogRes.show();


    }

    private void downloadCardImages() {
        AlertDialog.Builder cardDownloader = new AlertDialog.Builder(this);

        cardDownloader.setTitle("Which Sets would you like to download?");
        selectedSets = new ArrayList<String>();

        cardDownloader.setMultiChoiceItems(availableSets, checkedSet, new DialogInterface.OnMultiChoiceClickListener() {
            public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                checkedSet[which] = isChecked;
                if (checkedSet[which])
                    selectedSets.add(availableSets[which].split(" - ")[0]);
                else
                    selectedSets.remove(availableSets[which].split(" - ")[0]);
            }
        });

        cardDownloader.setNeutralButton("Download All", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                selectedSets.clear();
                for (int i = 0; i < availableSets.length; i++) {
                    selectedSets.add(availableSets[i].split(" - ")[0]);
                }
                downloadCardImagesStart();
            }
        });

        cardDownloader.setPositiveButton("Download Selected", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (selectedSets.size() > 0)
                    downloadCardImagesStart();
                else
                    downloadCardImages();
            }
        });

        cardDownloader.create().show();
    }

    boolean error = false;
    String res = "";
    boolean dowloadInProgress = false;
    ProgressDialog cardDownloader;

    private void downloadCardImagesStart() {
        final Handler mHandler = new Handler();
        cardDownloader = new ProgressDialog(this);
        cardDownloader.setTitle("Downloading now set: " + set);
        cardDownloader.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        cardDownloader.setProgress(0);
        if (selectedSets.size() == 1)
            cardDownloader.setMessage("You choose to download just 1 set: Please don't quit Wagic or turn off Internet connection, you can hide this window and continue to play, a pop-up will notify the completion of download process.");
        else
            cardDownloader.setMessage("You choose to download " + selectedSets.size() + " sets: Please don't quit Wagic or turn off Internet connection, you can hide this window and continue to play, a pop-up will notify the completion of download process.");

        new Thread(new Runnable() {
            public void run() {
                dowloadInProgress = true;
                if (selectedSets != null) {
                    for (int i = 0; i < selectedSets.size(); i++) {
                        try {
                            set = selectedSets.get(i);
                            mHandler.post(new Runnable() {
                                public void run() {
                                    cardDownloader.setTitle("Downloading set: " + set);
                                }
                            });
                            String details = ImgDownloader.DownloadCardImages(set, availableSets, "High", getSystemStorageLocation(), getUserStorageLocation() + "sets/", cardDownloader);
                            if (!details.isEmpty()) {
                                if (!res.isEmpty())
                                    res = res + "\nSET " + set + ":\n" + details;
                                else
                                    res = "SET " + set + ":\n" + details;
                            }
                        } catch (Exception e) {
                            res = res + "\n" + e.getMessage();
                            error = true;
                        }
                    }
                    mHandler.post(new Runnable() {
                        public void run() {
                            downloadSelectedSetsCompleted(error, res);
                            dowloadInProgress = false;
                            cardDownloader.dismiss();
                        }
                    });
                }
            }
        }).start();

        cardDownloader.setButton(DialogInterface.BUTTON_POSITIVE, "Hide", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                cardDownloader.hide();
            }
        });

        cardDownloader.setButton(DialogInterface.BUTTON_NEGATIVE, "Stop Download", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                mHandler.post(new Runnable() {
                    public void run() {
                        downloadCardInterruped(set, cardDownloader.getProgress(), cardDownloader.getMax());
                        dowloadInProgress = false;
                        cardDownloader.dismiss();
                    }
                });
            }
        });

        cardDownloader.show();
    }

    private void downloadCardInterruped(String set, int cardsDownloaded, int total) {
        AlertDialog.Builder infoDialog = new AlertDialog.Builder(this);
        infoDialog.setTitle("Download of " + set + " has been interrupted!");
        infoDialog.setMessage("WARNING: Only " + cardsDownloaded + " of " + total + " total cards have been downloaded and zip archive (" + set + ".zip) has not been created. You have to start the download again in order to complete the entire set.");

        infoDialog.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                downloadCardImages();
            }
        });

        infoDialog.create().show();
    }

    private void downloadSelectedSetsCompleted(boolean error, String res) {
        AlertDialog.Builder infoDialog = new AlertDialog.Builder(this);
        if (!error) {
            infoDialog.setTitle("The download process has completed without any error");
            if (!res.isEmpty())
                infoDialog.setMessage("Following cards could not be downloaded:\n" + res);
        } else {
            infoDialog.setTitle("Some errors occurred during the process!");
            infoDialog.setMessage(res);
        }
        infoDialog.create().show();
        res = "";
        set = "";
        selectedSets = new ArrayList<String>();
        error = false;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        SubMenu settingsMenu = menu.addSubMenu(Menu.NONE, 1, 1, "Settings");
        menu.add(Menu.NONE, 2, 2, "Import Decks");
        menu.add(Menu.NONE, 3, 3, "Download Cards");
        menu.add(Menu.NONE, 4, 4, "About");
        settingsMenu.add(kStorageDataOptionsMenuId, kStorageDataOptionsMenuId, Menu.NONE, "Storage Data Options");
        // buildStorageOptionsMenu(settingsMenu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        int itemId = item.getItemId();
        if (itemId == kStorageDataOptionsMenuId) {
            displayStorageOptions();
        } else if (itemId == 2) {
            importDeckOptions();
        } else if (itemId == 3) {
            if (availableSets == null)
                loadAvailableSets();
            else {
                if (loadResInProgress) {
                    progressBarDialogRes.show();
                    progressBarDialogRes.show();
                } else if (dowloadInProgress) {
                    cardDownloader.show();
                    cardDownloader.show();
                } else
                    downloadCardImages();
            }
        } else if (itemId == 4) {
            // display some info about the app
            AlertDialog.Builder infoDialog = new AlertDialog.Builder(this);
            infoDialog.setTitle("Wagic Info");
            infoDialog.setMessage("Version " + getResources().getString(R.string.app_version));
            infoDialog.show();

        } else
            return super.onOptionsItemSelected(item);
        return true;
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_DOWNLOAD_PROGRESS:
                mProgressDialog = new ProgressDialog(this);
                mProgressDialog.setMessage("Downloading resource files (" + RES_FILENAME + ")");
                mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                mProgressDialog.setCancelable(false);
                mProgressDialog.show();
                return mProgressDialog;
            case DIALOG_DOWNLOAD_ERROR:
                // prepare alertDialog
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage(mErrorMessage).setCancelable(false).setPositiveButton("Exit", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        System.exit(0);
                    }
                });
                mErrorDialog = builder.create();
                mErrorDialog.show();
                return mErrorDialog;
            default:
                return null;
        }
    }

    // Load the .so
    static {
        System.loadLibrary("SDL");
        // System.loadLibrary("SDL_image");
        // System.loadLibrary("SDL_mixer");
        // System.loadLibrary("SDL_ttf");
        System.loadLibrary("main");
    }

    // create main application
    public void mainDisplay() {
        FrameLayout _videoLayout = new FrameLayout(this);

        // mGLView = new DemoGLSurfaceView(this);

        // Set up the surface
        mSurface = new SDLSurface(getApplication());

        // setContentView(mSurface);
        SurfaceHolder holder = mSurface.getHolder();
        holder.setType(SurfaceHolder.SURFACE_TYPE_GPU);

        _videoLayout.addView(mSurface, new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));
        // mGLView.setFocusableInTouchMode(true);
        // mGLView.setFocusable(true);
        // adView.requestFreshAd();
        setContentView(_videoLayout, new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));
        mSurface.requestFocus();
    }

    // Setup
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //Log.d(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        // So we can call stuff from static callbacks
        mSingleton = this;
        mContext = this.getApplicationContext();
        //RES_FILENAME = getResourceName();
        StorageOptions.determineStorageOptions();
        checkStorageLocationPreference();
    }

    public void initializeGame() {
        String coreFileLocation = getSystemStorageLocation() + RES_FILENAME;

        File file = new File(coreFileLocation);

        if (file.exists()) {
            mainDisplay();
        } else {
            FrameLayout _videoLayout = new FrameLayout(this);
            setContentView(_videoLayout, new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));
            startDownload();
        }
    }

    // Events
    @Override
    protected void onPause() {
        // Log.d(TAG, "onPause()");
        super.onPause();
        SDLActivity.nativePause();
    }

    @Override
    protected void onResume() {
        // Log.d(TAG, "onResume()");
        super.onResume();
        SDLActivity.nativeResume();
    }

    @Override
    public void onDestroy() {
        // Log.d(TAG, "onDestroy()");
        super.onDestroy();
        mSurface.onDestroy();
    }

    // Handler for Messages coming from JGE
    // Suggested syntax for JGE messages is a string separated by the ":" symbol
    protected void processJGEMsg(String command) {
        if (null == command) {
            return;
        }
    }

    // Messages from the SDLMain thread
    static int COMMAND_CHANGE_TITLE = 1;
    static int COMMAND_JGE_MSG = 2;

    // Handler for the messages
    Handler commandHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.arg1 == COMMAND_CHANGE_TITLE) {
                setTitle((String) msg.obj);
            } else if (msg.arg1 == COMMAND_JGE_MSG) {
                processJGEMsg((String) msg.obj);
            }
        }
    };

    // Send a message from the SDLMain thread
    void sendCommand(int command, Object data) {
        Message msg = commandHandler.obtainMessage();
        msg.arg1 = command;
        msg.obj = data;
        commandHandler.sendMessage(msg);
    }

    // C functions we call
    public static native String getResourceUrl();

    public static native String getResourceName();

    public static native void nativeInit();

    public static native void nativeQuit();

    public static native void nativePause();

    public static native void nativeResume();

    public static native void onNativeResize(int x, int y, int format);

    public static native void onNativeKeyDown(int keycode);

    public static native void onNativeKeyUp(int keycode);

    public static native void onNativeTouch(int index, int action, float x, float y, float p);

    public static native void onNativeFlickGesture(float xVelocity, float yVelocity);

    public static native void onNativeAccel(float x, float y, float z);

    public static native void nativeRunAudioThread();

    // Java functions called from C
    // Receive a message from the SDLMain thread
    public static String getSystemFolderPath() {
        return mSingleton.getSystemStorageLocation();
    }

    public static String getUserFolderPath() {
        return mSingleton.getUserStorageLocation();
    }

    public static void jgeSendCommand(String command) {
        mSingleton.sendCommand(COMMAND_JGE_MSG, command);
    }

    public static boolean createGLContext(int majorVersion, int minorVersion) {
        return mSurface.initEGL(majorVersion, minorVersion);
    }

    public static void flipBuffers() {
        mSurface.flipEGL();
    }

    public static void setActivityTitle(String title) {
        // Called from SDLMain() thread and can't directly affect the view
        mSingleton.sendCommand(COMMAND_CHANGE_TITLE, title);
    }

    // Audio
    private static Object buf;

    public static Object audioInit(int sampleRate, boolean is16Bit, boolean isStereo, int desiredFrames) {
        int channelConfig = isStereo ? AudioFormat.CHANNEL_CONFIGURATION_STEREO : AudioFormat.CHANNEL_CONFIGURATION_MONO;
        int audioFormat = is16Bit ? AudioFormat.ENCODING_PCM_16BIT : AudioFormat.ENCODING_PCM_8BIT;
        int frameSize = (isStereo ? 2 : 1) * (is16Bit ? 2 : 1);

        // Log.d(TAG, "SDL audio: wanted " + (isStereo ? "stereo" : "mono") + " " + (is16Bit ? "16-bit" : "8-bit") + " " + ((float)sampleRate / 1000f) + "kHz, " + desiredFrames + " frames buffer");

        // Let the user pick a larger buffer if they really want -- but ye
        // gods they probably shouldn't, the minimums are horrifyingly high
        // latency already
        desiredFrames = Math.max(desiredFrames, (AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) + frameSize - 1) / frameSize);

        mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig, audioFormat, desiredFrames * frameSize, AudioTrack.MODE_STREAM);

        audioStartThread();

        // Log.d(TAG, "SDL audio: got " + ((mAudioTrack.getChannelCount() >= 2) ? "stereo" : "mono") + " " + ((mAudioTrack.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT) ? "16-bit" : "8-bit") + " " + ((float)mAudioTrack.getSampleRate() / 1000f) +
        // "kHz, " + desiredFrames + " frames buffer");

        if (is16Bit) {
            buf = new short[desiredFrames * (isStereo ? 2 : 1)];
        } else {
            buf = new byte[desiredFrames * (isStereo ? 2 : 1)];
        }
        return buf;
    }

    public static void audioStartThread() {
        mAudioThread = new Thread(new Runnable() {
            public void run() {
                mAudioTrack.play();
                nativeRunAudioThread();
            }
        });

        // I'd take REALTIME if I could get it!
        mAudioThread.setPriority(Thread.MAX_PRIORITY);
        mAudioThread.start();
    }

    public static void audioWriteShortBuffer(short[] buffer) {
        for (int i = 0; i < buffer.length; ) {
            int result = mAudioTrack.write(buffer, i, buffer.length - i);
            if (result > 0) {
                i += result;
            } else if (result == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    // Nom nom
                }
            } else {
                Log.w(TAG, "SDL audio: error return from write(short)");
                return;
            }
        }
    }

    public static void audioWriteByteBuffer(byte[] buffer) {
        for (int i = 0; i < buffer.length; ) {
            int result = mAudioTrack.write(buffer, i, buffer.length - i);
            if (result > 0) {
                i += result;
            } else if (result == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    // Nom nom
                }
            } else {
                Log.w(TAG, "SDL audio: error return from write(short)");
                return;
            }
        }
    }

    public static void audioQuit() {
        if (mAudioThread != null) {
            try {
                mAudioThread.join();
            } catch (Exception e) {
                Log.e(TAG, "Problem stopping audio thread: " + e);
            }
            mAudioThread = null;

            // Log.d(TAG, "Finished waiting for audio thread");
        }

        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack = null;
        }
    }

    class DownloadFileAsync extends AsyncTask<String, Integer, Long> {
        private final String TAG = DownloadFileAsync.class.getCanonicalName();

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            showDialog(DIALOG_DOWNLOAD_PROGRESS);
        }

        @Override
        protected Long doInBackground(String... aurl) {
            int count;
            long totalBytes = 0;
            OutputStream output = null;
            InputStream input = null;

            try {
                //
                // Prepare the sdcard folders in order to download the resource file
                //

                String storageLocation = mSingleton.getSystemStorageLocation();

                File resDirectory = new File(storageLocation);
                File userDirectory = new File(mSingleton.getUserStorageLocation());

                if (!resDirectory.exists() && !resDirectory.mkdirs() || (!userDirectory.exists() && !userDirectory.mkdirs())) {
                    throw new Exception("Failed to initialize system and user directories.");
                }

                URL url = new URL(aurl[0]);
                String filename = url.getPath().substring(url.getPath().lastIndexOf('/') + 1);
                URLConnection conexion = url.openConnection();
                conexion.connect();

                int lengthOfFile = conexion.getContentLength();
                // Log.d(TAG, " Length of file: " + lengthOfFile);

                input = new BufferedInputStream(url.openStream());
                // create a File object for the output file
                File outputFile = new File(resDirectory, filename);

                output = new FileOutputStream(outputFile);

                byte data[] = new byte[1024];
                while ((count = input.read(data)) != -1) {
                    totalBytes += count;
                    publishProgress((int) ((totalBytes * 100) / lengthOfFile));
                    output.write(data, 0, count);
                }

                output.flush();
                output.close();
                input.close();
            } catch (Exception e) {
                String errorMessage = "An error happened while downloading the resources. It could be that our server is temporarily down, that your device is not connected to a network, or that we cannot write to " + mSingleton.getSystemStorageLocation() + ". Please check your phone settings and try again. For more help please go to http://wagic.net";
                //mSingleton.downloadError(errorMessage);
                //Log.e(TAG, errorMessage);
                //Log.e(TAG, e.getMessage());
            }

            return Long.valueOf(totalBytes);
        }

        protected void onProgressUpdate(Integer... progress) {
            if (progress[0] != mProgressDialog.getProgress()) {
                // Log.d(TAG, "current progress : " + progress[0]);
                mProgressDialog.setProgress(progress[0]);
            }
        }

        @Override
        protected void onPostExecute(Long unused) {
            if (mErrorHappened) {
                dismissDialog(DIALOG_DOWNLOAD_PROGRESS);
                showDialog(DIALOG_DOWNLOAD_ERROR);
                return;
            }
            // rename the temporary file into the final filename
            String storageLocation = getSystemStorageLocation();

            File preFile = new File(storageLocation + RES_FILENAME + ".tmp");
            File postFile = new File(storageLocation + RES_FILENAME);

            if (preFile.exists())
                preFile.renameTo(postFile);

            dismissDialog(DIALOG_DOWNLOAD_PROGRESS);
            // Start game;
            mSingleton.mainDisplay();
        }
    }

    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_MENU) && (KeyEvent.ACTION_DOWN == event.getAction())) {
            super.onKeyDown(keyCode, event);
            return true;
        } else if ((keyCode == KeyEvent.KEYCODE_MENU) && (KeyEvent.ACTION_UP == event.getAction())) {
            super.onKeyUp(keyCode, event);
            return true;
        }

        return false;
    }

    private String getApplicationCode() {
        int v = 0;
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (NameNotFoundException e) {
            // Huh? Really?
            v = 184; // shouldn't really happen but we need to default to something
        }
        return "0" + v;
    }

    // Empty onConfigurationChanged to stop the Activity from destroying/recreating on screen off
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }
}

/**
 * SDLSurface. This is what we draw on, so we need to know when it's created in order to do anything useful.
 * <p>
 * Because of this, that's where we set up the SDL thread
 */
class SDLSurface extends SurfaceView implements SurfaceHolder.Callback, View.OnKeyListener, View.OnTouchListener, SensorEventListener {
    private static final String TAG = SDLSurface.class.getCanonicalName();

    // This is what SDL runs in. It invokes SDL_main(), eventually
    private Thread mSDLThread;

    // EGL private objects
    private EGLContext mEGLContext;
    private EGLSurface mEGLSurface;
    private EGLDisplay mEGLDisplay;
    private EGLConfig mEGLConfig;

    // Sensors
    private static SensorManager mSensorManager;

    private static VelocityTracker mVelocityTracker;

    final private Object mSemSurface;
    private Boolean mSurfaceValid;

    void startSDLThread() {
        if (mSDLThread == null) {
            mSDLThread = new Thread(new SDLMain(), "SDLThread");
            mSDLThread.start();
        }
    }

    /**
     * Simple nativeInit() runnable
     */
    class SDLMain implements Runnable {
        public void run() {
            // Runs SDL_main()
            SDLActivity.nativeInit();

            SDLActivity.nativeQuit();

            Log.d(TAG, "SDL thread terminated");

            // On exit, tear everything down for a fresh restart next time.
            System.exit(0);
        }
    }

    // Startup
    public SDLSurface(Context context) {
        super(context);
        mSemSurface = new Object();
        mSurfaceValid = false;
        getHolder().addCallback(this);

        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setOnKeyListener(this);
        setOnTouchListener(this);

        mSensorManager = (SensorManager) context.getSystemService("sensor");
    }

    // Called when we have a valid drawing surface
    public void surfaceCreated(SurfaceHolder holder) {
        //Log.d(TAG, "surfaceCreated()");

        enableSensor(Sensor.TYPE_ACCELEROMETER, true);
    }

    public void onDestroy() {
        // Send a quit message to the application
        // should that be in SDLActivity "onDestroy" instead ?

        SDLActivity.nativeQuit();

        // Now wait for the SDL thread to quit
        if (mSDLThread != null) {
            try {
                mSDLThread.join();
            } catch (Exception e) {
                Log.e(TAG, "Problem stopping thread: " + e);
            }
            mSDLThread = null;

            // Log.d(TAG, "Finished waiting for SDL thread");
        }
    }

    // Called when we lose the surface
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed()");
        synchronized (mSemSurface) {
            mSurfaceValid = false;
            mSemSurface.notifyAll();
        }
        enableSensor(Sensor.TYPE_ACCELEROMETER, false);
    }

    // Called when the surface is resized
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged()");

        int sdlFormat = 0x85151002; // SDL_PIXELFORMAT_RGB565 by default
        switch (format) {
            case PixelFormat.A_8:
                Log.d("TAG", "pixel format A_8");
                break;
            case PixelFormat.LA_88:
                Log.d("TAG", "pixel format LA_88");
                break;
            case PixelFormat.L_8:
                Log.d("TAG", "pixel format L_8");
                break;
            case PixelFormat.RGBA_4444:
                Log.d("TAG", "pixel format RGBA_4444");
                sdlFormat = 0x85421002; // SDL_PIXELFORMAT_RGBA4444
                break;
            case PixelFormat.RGBA_5551:
                Log.d(TAG, "pixel format RGBA_5551");
                sdlFormat = 0x85441002; // SDL_PIXELFORMAT_RGBA5551
                break;
            case PixelFormat.RGBA_8888:
                Log.d(TAG, "pixel format RGBA_8888");
                sdlFormat = 0x86462004; // SDL_PIXELFORMAT_RGBA8888
                break;
            case PixelFormat.RGBX_8888:
                Log.d(TAG, "pixel format RGBX_8888");
                sdlFormat = 0x86262004; // SDL_PIXELFORMAT_RGBX8888
                break;
            case PixelFormat.RGB_332:
                Log.d(TAG, "pixel format RGB_332");
                sdlFormat = 0x84110801; // SDL_PIXELFORMAT_RGB332
                break;
            case PixelFormat.RGB_565:
                Log.d(TAG, "pixel format RGB_565");
                sdlFormat = 0x85151002; // SDL_PIXELFORMAT_RGB565
                break;
            case PixelFormat.RGB_888:
                Log.d(TAG, "pixel format RGB_888");
                // Not sure this is right, maybe SDL_PIXELFORMAT_RGB24 instead?
                sdlFormat = 0x86161804; // SDL_PIXELFORMAT_RGB888
                break;
            default:
                Log.d(TAG, "pixel format unknown " + format);
                break;
        }
        SDLActivity.onNativeResize(width, height, sdlFormat);

        // Now start up the C app thread
        startSDLThread();
    }

    // unused
    public void onDraw(Canvas canvas) {
    }

    // EGL functions
    public boolean initEGL(int majorVersion, int minorVersion) {
        Log.d(TAG, "Starting up OpenGL ES " + majorVersion + "." + minorVersion);

        try {
            EGL10 egl = (EGL10) EGLContext.getEGL();

            EGLDisplay dpy = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

            int[] version = new int[2];
            egl.eglInitialize(dpy, version);

            int EGL_OPENGL_ES_BIT = 1;
            int EGL_OPENGL_ES2_BIT = 4;
            int renderableType = 0;
            if (majorVersion == 2) {
                renderableType = EGL_OPENGL_ES2_BIT;
            } else if (majorVersion == 1) {
                renderableType = EGL_OPENGL_ES_BIT;
            }
            int[] configSpec =
                    {
                            // EGL10.EGL_DEPTH_SIZE, 16,
                            EGL10.EGL_RENDERABLE_TYPE, renderableType, EGL10.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] num_config = new int[1];
            if (!egl.eglChooseConfig(dpy, configSpec, configs, 1, num_config) || num_config[0] == 0) {
                Log.e(TAG, "No EGL config available");
                return false;
            }
            mEGLConfig = configs[0];

            EGLContext ctx = egl.eglCreateContext(dpy, mEGLConfig, EGL10.EGL_NO_CONTEXT, null);
            if (ctx == EGL10.EGL_NO_CONTEXT) {
                Log.e(TAG, "Couldn't create context");
                return false;
            }

            mEGLContext = ctx;
            mEGLDisplay = dpy;

            if (!createSurface(this.getHolder())) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, e + "");
            for (StackTraceElement s : e.getStackTrace()) {
                Log.e(TAG, s.toString());
            }
        }

        return true;
    }

    public Boolean createSurface(SurfaceHolder holder) {
        /*
         * The window size has changed, so we need to create a new surface.
         */
        EGL10 egl = (EGL10) EGLContext.getEGL();
        if (mEGLSurface != null) {
            /*
             * Unbind and destroy the old EGL surface, if there is one.
             */
            egl.eglMakeCurrent(mEGLDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            egl.eglDestroySurface(mEGLDisplay, mEGLSurface);
        }

        /*
         * Create an EGL surface we can render into.
         */
        mEGLSurface = egl.eglCreateWindowSurface(mEGLDisplay, mEGLConfig, holder, null);
        if (mEGLSurface == EGL10.EGL_NO_SURFACE) {
            Log.e(TAG, "Couldn't create surface");
            return false;
        }

        /*
         * Before we can issue GL commands, we need to make sure the context is current and bound to a surface.
         */
        if (!egl.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            Log.e(TAG, "Couldn't make context current");
            return false;
        }

        mSurfaceValid = true;

        return true;
    }

    // EGL buffer flip
    public void flipEGL() {
        if (!mSurfaceValid) {
            createSurface(this.getHolder());
        }

        try {
            EGL10 egl = (EGL10) EGLContext.getEGL();

            egl.eglWaitNative(EGL10.EGL_CORE_NATIVE_ENGINE, null);

            // drawing here

            egl.eglWaitGL();

            egl.eglSwapBuffers(mEGLDisplay, mEGLSurface);

        } catch (Exception e) {
            Log.e(TAG, "flipEGL(): " + e);
            for (StackTraceElement s : e.getStackTrace()) {
                Log.e(TAG, s.toString());
            }
        }
    }

    // Key events
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU)
            return false;

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // Log.d(TAG, "key down: " + keyCode);
            SDLActivity.onNativeKeyDown(keyCode);
            return true;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            // Log.d(TAG, "key up:   " + keyCode);
            SDLActivity.onNativeKeyUp(keyCode);
            return true;
        }

        return false;
    }

    // Touch events
    public boolean onTouch(View v, MotionEvent event) {
        for (int index = 0; index < event.getPointerCount(); ++index) {
            int action = event.getActionMasked();
            float x = event.getX(index);
            float y = event.getY(index);
            float p = event.getPressure(index);

            // TODO: Anything else we need to pass?
            SDLActivity.onNativeTouch(index, action, x, y, p);
        }

        // account for 'flick' type gestures by monitoring velocity
        if (event.getActionIndex() == 0) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                mVelocityTracker = VelocityTracker.obtain();
                mVelocityTracker.clear();
                mVelocityTracker.addMovement(event);
            } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                mVelocityTracker.addMovement(event);
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                mVelocityTracker.addMovement(event);

                // calc velocity
                mVelocityTracker.computeCurrentVelocity(1000);
                float xVelocity = mVelocityTracker.getXVelocity(0);
                float yVelocity = mVelocityTracker.getYVelocity(0);
                if (Math.abs(xVelocity) > 300 || Math.abs(yVelocity) > 300)
                    SDLActivity.onNativeFlickGesture(xVelocity, yVelocity);

                mVelocityTracker.recycle();
            }
        }

        return true;
    }

    // Sensor events
    public void enableSensor(int sensortype, boolean enabled) {
        // TODO: This uses getDefaultSensor - what if we have >1 accels?
        if (enabled) {
            mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(sensortype), SensorManager.SENSOR_DELAY_GAME, null);
        } else {
            mSensorManager.unregisterListener(this, mSensorManager.getDefaultSensor(sensortype));
        }
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // TODO
    }

    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            SDLActivity.onNativeAccel(event.values[0], event.values[1], event.values[2]);
        }
    }
}

