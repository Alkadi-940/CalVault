package dev.calvault;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;

/** Synthetic documents and grants exist exclusively in the test APK. */
public class FixtureDocumentsProvider extends DocumentsProvider {
    private File root() { File f = new File(getContext().getCacheDir(), "document-fixtures"); f.mkdirs(); return f; }
    private File file(String id) throws FileNotFoundException {
        try {
            if (!id.equals("root") && !id.startsWith("root/")) throw new IOException("Invalid id");
            File f = new File(root(), id.equals("root") ? "" : id.substring(5)).getCanonicalFile();
            if (!f.equals(root().getCanonicalFile()) && !f.getPath().startsWith(root().getCanonicalPath() + File.separator)) throw new IOException("Invalid path");
            return f;
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection == null ? new String[]{"root_id", "document_id", "title", "flags"} : projection);
        c.newRow().add("root_id", "fixture").add("document_id", "root").add("title", "CalVault test files").add("flags", DocumentsContract.Root.FLAG_SUPPORTS_CREATE);
        return c;
    }
    private MatrixCursor cursor(String[] projection) { return new MatrixCursor(projection == null ? new String[]{"document_id", "_display_name", "mime_type", "flags", "_size"} : projection); }
    private void row(MatrixCursor c, String id) throws FileNotFoundException {
        File f = file(id);
        if (!f.exists()) throw new FileNotFoundException(id);
        int flags = f.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : f.getName().startsWith("readonly") ? 0 : DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        c.newRow().add("document_id", id).add("_display_name", f.getName()).add("mime_type", f.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "text/plain").add("flags", flags).add("_size", f.length());
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException { MatrixCursor c = cursor(projection); row(c, id); return c; }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sort) throws FileNotFoundException {
        MatrixCursor c = cursor(projection); File[] files = file(id).listFiles();
        if (files != null) for (File f : files) row(c, id + "/" + f.getName());
        return c;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException { return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode)); }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (name.contains("/") || name.contains("\\")) throw new FileNotFoundException("Invalid name");
        String id = parent + "/" + name;
        try { if (!file(id).createNewFile()) throw new IOException("Already exists"); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        return id;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException { if (file(id).getName().startsWith("readonly") || !file(id).delete()) throw new FileNotFoundException("Not deletable"); }
    @Override public boolean isChildDocument(String parent, String id) { return id.startsWith(parent + "/"); }
    @Override public Bundle call(String method, String name, Bundle extras) {
        if (!"seed".equals(method)) return super.call(method, name, extras);
        try {
            String id = "root/" + name;
            try (FileOutputStream out = new FileOutputStream(file(id))) { out.write(extras.getByteArray("bytes")); }
            Uri uri = DocumentsContract.buildDocumentUri("dev.calvault.test.documents", id);
            Uri tree = DocumentsContract.buildTreeDocumentUri("dev.calvault.test.documents", "root");
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
            getContext().grantUriPermission("dev.calvault", uri, flags);
            getContext().grantUriPermission("dev.calvault", tree, flags | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            return new Bundle();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
