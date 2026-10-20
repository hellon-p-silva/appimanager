package com.hellon.appimanager;

import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

final class FileUtils {

    private FileUtils() {
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit]);
    }

    static String formatDate(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(millis));
    }

    static String extension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    static String mimeType(File file) {
        String ext = extension(file);
        String mime = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "*/*";
    }

    static int iconFor(File file) {
        if (file.isDirectory()) return R.drawable.ic_folder;
        String mime = mimeType(file);
        String ext = extension(file);
        if (mime.startsWith("image/")) return R.drawable.ic_image;
        if (mime.startsWith("video/")) return R.drawable.ic_video;
        if (mime.startsWith("audio/")) return R.drawable.ic_audio;
        if (ext.equals("apk")) return R.drawable.ic_apk;
        switch (ext) {
            case "zip": case "rar": case "7z": case "tar": case "gz": case "bz2": case "xz":
                return R.drawable.ic_archive;
            case "pdf": case "doc": case "docx": case "txt": case "odt": case "rtf":
            case "xls": case "xlsx": case "ppt": case "pptx": case "md": case "csv":
                return R.drawable.ic_doc;
            default:
                return R.drawable.ic_file;
        }
    }

    /** Tamanho total de um arquivo ou de uma pasta (recursivo). */
    static long sizeOf(File file) {
        if (!file.isDirectory()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) total += sizeOf(child);
        }
        return total;
    }

    static int countChildren(File dir, boolean includeHidden) {
        String[] names = dir.list();
        if (names == null) return 0;
        if (includeHidden) return names.length;
        int count = 0;
        for (String n : names) if (!n.startsWith(".")) count++;
        return count;
    }

    static boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) return false;
                }
            }
        }
        return file.delete() || !file.exists();
    }

    static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("Não foi possível criar " + dst);
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) copyRecursive(child, new File(dst, child.getName()));
            }
        } else {
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            //noinspection ResultOfMethodCallIgnored
            dst.setLastModified(src.lastModified());
        }
    }

    static void moveRecursive(File src, File dst) throws IOException {
        if (src.renameTo(dst)) return;
        copyRecursive(src, dst);
        if (!deleteRecursive(src)) throw new IOException("Não foi possível remover " + src);
    }

    /** Retorna um destino que não existe, acrescentando " (n)" ao nome se necessário. */
    static File uniqueTarget(File dir, String name) {
        File target = new File(dir, name);
        if (!target.exists()) return target;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; ; i++) {
            target = new File(dir, base + " (" + i + ")" + ext);
            if (!target.exists()) return target;
        }
    }

    /** true se {@code child} for igual a {@code parent} ou estiver dentro dele. */
    static boolean isInside(File child, File parent) {
        String c = child.getAbsolutePath() + "/";
        String p = parent.getAbsolutePath() + "/";
        return c.startsWith(p);
    }

    static boolean isValidName(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..") && !name.contains("/");
    }
}
