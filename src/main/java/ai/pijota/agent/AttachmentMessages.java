package ai.pijota.agent;

import java.util.Locale;

public final class AttachmentMessages {
    private AttachmentMessages() {}

    public static String unsupportedImage(String name, String provider, String model) {
        return "attachment \"" + name + "\" omitted — " + unsupportedImageLabel(name)
                                        + " images aren't supported by " + modelLabel(provider, model)
                                        + "; use JPEG/PNG/GIF/WebP";
    }

    public static String unsupportedImagePath(String path) {
        return path + " — " + unsupportedImageLabel(path) + " images aren't supported; use JPEG/PNG/GIF/WebP";
    }

    public static String unsupportedImageLabel(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".heic") || n.endsWith(".heif") || n.contains("/heic") || n.contains("/heif"))
            return "HEIC";
        if (n.endsWith(".tif") || n.endsWith(".tiff") || n.contains("/tiff"))
            return "TIFF";
        if (n.endsWith(".bmp") || n.contains("/bmp"))
            return "BMP";
        int slash = n.indexOf('/');
        return slash >= 0 ? n.substring(slash + 1).toUpperCase(Locale.ROOT) : "this format";
    }

    public static String modelLabel(String provider, String model) {
        if (provider != null && !provider.isBlank() && model != null && !model.isBlank())
            return provider + "/" + model;
        if (model != null && !model.isBlank())
            return model;
        return "this model";
    }
}
