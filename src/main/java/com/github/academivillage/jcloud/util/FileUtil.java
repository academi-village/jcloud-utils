package com.github.academivillage.jcloud.util;

import lombok.val;
import lombok.var;

public class FileUtil {

    public static String getParentDirPath(String path, char separator) {
        int lastSlashIdx = path.lastIndexOf(separator);
        return lastSlashIdx <= 0 ? "" : path.substring(0, lastSlashIdx);
    }

    public static String getParentDirPath(String path) {
        return getParentDirPath(path, '/');
    }

    public static String getFileName(String storagePath) {
        int lastSlashIdx = storagePath.lastIndexOf('/');
        var fileName     = lastSlashIdx <= 0 ? storagePath : storagePath.substring(lastSlashIdx + 1);
        int qmIndex      = fileName.indexOf('?');

        val isUrl             = storagePath.startsWith("https://") || storagePath.startsWith("http://");
        val questionMarkFound = qmIndex > 0;

        return !questionMarkFound || !isUrl ? fileName : fileName.substring(0, qmIndex);
    }
}
