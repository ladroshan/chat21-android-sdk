package chat21.android.storage;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.storage.OnPausedListener;
import com.google.firebase.storage.OnProgressListener;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import chat21.android.dao.node.NodeDAOImpl;
import chat21.android.utils.IOUtils;
import chat21.android.utils.ImageCompressor;

/**
 * Created by stefanodp91 on 02/08/17.
 * bugfix Issue #15
 */
public class StorageHandler {
    private static final String TAG = StorageHandler.class.getName();

    public static void uploadFile(Context context, File fileToUpload,
                                  final OnUploadedCallback callback) {
        Log.d(TAG, "uploadFile");

        Uri file = Uri.fromFile(fileToUpload);

        // check the type
        // if it is an image compress it
        IOUtils.Type type = IOUtils.getType(fileToUpload);
        String typeStr = type.toString().toLowerCase();
        if (type.equals(IOUtils.Type.Image)) {
            // its an image
            compressImage(context, file, typeStr, callback); // compress and upload
        } else {
            // its a file
            performUpload(context, file, typeStr, callback); // just upload
        }
    }

    // compress the image keeping ratio and quality
    private static void compressImage(final Context context, Uri file, final String type,
                                      final OnUploadedCallback callback) {
        ImageCompressor.compress(context, file, new ImageCompressor.OnImageCompressListener() {
            @Override
            public void onImageCompressed(Uri path) {
                performUpload(context, path, type, callback);
            }
        });
    }

    // execute the upload
    private static void performUpload(Context context, Uri file, final String type,
                                      final OnUploadedCallback callback) {
        // public folder
        StorageReference storageRef = new NodeDAOImpl(context)
                .getPublicStorageFolder();

        // random uid.
        // this is used to generate an unique folder in which
        // upload the file to preserve the filename
        String uuid = UUID.randomUUID().toString();

        // upload to /public/images/uuid/file.ext
        StorageReference riversRef = storageRef.child(type.toString() + "/" + uuid + "/" +
                file.getLastPathSegment());
        UploadTask uploadTask = riversRef.putFile(file);

        // Register observers to listen for when the download is done or if it fails
        uploadTask
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception exception) {
                        // Handle unsuccessful uploads
                        Log.e(TAG, "addOnFailureListener.onFailure: " + exception.getMessage());

                        callback.onUploadFailed(exception);
                    }
                })
//                .addOnProgressListener(new OnProgressListener<UploadTask.TaskSnapshot>() {
//                    @Override
//                    public void onProgress(UploadTask.TaskSnapshot taskSnapshot) {
//                        // progress
//                        double progress = (100.0 * taskSnapshot.getBytesTransferred()) /
//                                taskSnapshot.getTotalByteCount();
//                        Log.d(TAG, "addOnFailureListener.onProgress - progress: " + progress);
//
//                        callback.onProgress(progress);
//                    }
//                })
                .addOnProgressListener(new OnProgressListener<UploadTask.TaskSnapshot>() {
                    @Override
                    public void onProgress(UploadTask.TaskSnapshot taskSnapshot) {
                        double progress = (100.0 * taskSnapshot.getBytesTransferred()) / taskSnapshot.getTotalByteCount();
                        Log.d(TAG, "Upload is " + progress + "% done");
                        int currentProgress = (int) progress;
                        callback.onProgress(currentProgress);
                    }
                }).addOnPausedListener(new OnPausedListener<UploadTask.TaskSnapshot>() {
            @Override
            public void onPaused(UploadTask.TaskSnapshot taskSnapshot) {
                System.out.println("Upload is paused");
            }
        })
                .addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
                    @Override
                    public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
                        // taskSnapshot.getMetadata() contains file metadata such as size,
                        // content-type, and download URL.
                        Uri downloadUrl = taskSnapshot.getDownloadUrl();
                        Log.d(TAG, "addOnFailureListener.onSuccess - downloadUrl: " + downloadUrl);

                        callback.onUploadSuccess(downloadUrl, type);
                    }
                });
    }

    public static String getFilePathFromUri(Context context, Uri uri) {
        boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
        String filePath = "";
        boolean isImageFromGoogleDrive = false;

        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                String docId = DocumentsContract.getDocumentId(uri);
                String[] split = docId.split(":");
                String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    filePath = Environment.getExternalStorageDirectory() + "/" + split[1];
                } else {
                    Pattern DIR_SEPORATOR = Pattern.compile("/");
                    Set<String> rv = new HashSet<>();
                    String rawExternalStorage = System.getenv("EXTERNAL_STORAGE");
                    String rawSecondaryStoragesStr = System.getenv("SECONDARY_STORAGE");
                    String rawEmulatedStorageTarget = System.getenv("EMULATED_STORAGE_TARGET");
                    if (TextUtils.isEmpty(rawEmulatedStorageTarget)) {
                        if (TextUtils.isEmpty(rawExternalStorage)) {
                            rv.add("/storage/sdcard0");
                        } else {
                            rv.add(rawExternalStorage);
                        }
                    } else {
                        String rawUserId;
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                            rawUserId = "";
                        } else {
                            String path = Environment
                                    .getExternalStorageDirectory()
                                    .getAbsolutePath();
                            String[] folders = DIR_SEPORATOR.split(path);
                            String lastFolder = folders[folders.length - 1];
                            boolean isDigit = false;
                            try {
                                Integer.valueOf(lastFolder);
                                isDigit = true;
                            } catch (NumberFormatException ignored) {
                            }
                            rawUserId = isDigit ? lastFolder : "";
                        }
                        if (TextUtils.isEmpty(rawUserId)) {
                            rv.add(rawEmulatedStorageTarget);
                        } else {
                            rv.add(rawEmulatedStorageTarget + File.separator + rawUserId);
                        }
                    }
                    if (!TextUtils.isEmpty(rawSecondaryStoragesStr)) {
                        String[] rawSecondaryStorages =
                                rawSecondaryStoragesStr.split(File.pathSeparator);
                        Collections.addAll(rv, rawSecondaryStorages);
                    }
                    String[] temp = rv.toArray(new String[rv.size()]);
                    for (int i = 0; i < temp.length; i++) {
                        File tempf = new File(temp[i] + "/" + split[1]);
                        if (tempf.exists()) {
                            filePath = temp[i] + "/" + split[1];
                        }
                    }
                }
            } else if ("com.android.providers.downloads.documents"
                    .equals(uri.getAuthority())) {
                String id = DocumentsContract.getDocumentId(uri);
                Uri contentUri = ContentUris
                        .withAppendedId(Uri
                                        .parse("content://downloads/public_downloads"),
                                Long.valueOf(id));

                Cursor cursor = null;
                String column = "_data";
                String[] projection = {column};
                try {
                    cursor = context.getContentResolver().query(contentUri,
                            projection, null, null,
                            null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int column_index = cursor.getColumnIndexOrThrow(column);
                        filePath = cursor.getString(column_index);
                    }
                } finally {
                    if (cursor != null)
                        cursor.close();
                }
            } else if ("com.android.providers.media.documents"
                    .equals(uri.getAuthority())) {
                String docId = DocumentsContract.getDocumentId(uri);
                String[] split = docId.split(":");
                String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                String selection = "_id=?";
                String[] selectionArgs = new String[]{split[1]};

                Cursor cursor = null;
                String column = "_data";
                String[] projection = {column};

                try {
                    cursor = context.getContentResolver().query(contentUri, projection,
                            selection, selectionArgs, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int column_index = cursor.getColumnIndexOrThrow(column);
                        filePath = cursor.getString(column_index);
                    }
                } finally {
                    if (cursor != null)
                        cursor.close();
                }
            } else if ("com.google.android.apps.docs.storage".equals(uri.getAuthority())) {
                isImageFromGoogleDrive = true;
            }
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            Cursor cursor = null;
            String column = "_data";
            String[] projection = {column};

            try {
                cursor = context.getContentResolver().query(uri, projection, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int column_index = cursor.getColumnIndexOrThrow(column);
                    filePath = cursor.getString(column_index);
                }
            } finally {
                if (cursor != null)
                    cursor.close();
            }
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            filePath = uri.getPath();
        }

        if (isImageFromGoogleDrive) {
            try {
                return uri.toString();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            return filePath;
        }

        return null;
    }
}