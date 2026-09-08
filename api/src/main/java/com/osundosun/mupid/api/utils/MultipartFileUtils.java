package com.osundosun.mupid.api.utils;

import java.util.Set;

import org.springframework.web.multipart.MultipartFile;

public class MultipartFileUtils {

    private static final Set<String> IMAGE_EXT = Set.of(
        "jpg", "jpeg", "png", "gif", "webp"
    );
    
    public static boolean isImageExt(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }
        String ext = parseExt(file.getOriginalFilename());
        return IMAGE_EXT.contains(ext);
    }

    public static String parseExt(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }

        int lastDotIndex = filename.lastIndexOf(".");
        if (lastDotIndex == -1 || lastDotIndex == filename.length() - 1) {
            return "";
        }

        return filename.substring(lastDotIndex + 1).toLowerCase();
    }

}
