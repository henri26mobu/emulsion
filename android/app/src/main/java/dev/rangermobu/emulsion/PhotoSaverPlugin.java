package dev.rangermobu.emulsion;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Base64;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/** Pont natif : enregistrement dans la Galerie (MediaStore), partage sortant et réception d'une photo partagée. */
@CapacitorPlugin(name = "PhotoSaver")
public class PhotoSaverPlugin extends Plugin {
    private final Map<String, OutputStream> open = new HashMap<>();
    private volatile String pendingShared;
    private volatile String pendingType;

    // L'image arrive de JS par morceaux base64 : un JPEG pleine résolution est trop gros pour un seul appel.
    @PluginMethod
    public void begin(PluginCall call) {
        String name = call.getString("name", "emulsion.jpg").replaceAll("[^A-Za-z0-9._-]", "_");
        ContentResolver cr = getContext().getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Emulsion");
        v.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        try {
            if (uri == null) throw new IOException("MediaStore a refusé la création du fichier");
            open.put(uri.toString(), cr.openOutputStream(uri));
            JSObject r = new JSObject();
            r.put("id", uri.toString());
            call.resolve(r);
        } catch (Exception e) {
            if (uri != null) cr.delete(uri, null, null);
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void append(PluginCall call) {
        OutputStream os = open.get(call.getString("id"));
        if (os == null) {
            call.reject("écriture inconnue");
            return;
        }
        try {
            os.write(Base64.decode(call.getString("data"), Base64.DEFAULT));
            call.resolve();
        } catch (IOException e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void finish(PluginCall call) {
        String id = call.getString("id");
        OutputStream os = open.remove(id);
        try {
            if (os == null) throw new IOException("écriture inconnue");
            os.close();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.IS_PENDING, 0); // la photo devient visible dans la Galerie
            getContext().getContentResolver().update(Uri.parse(id), v, null, null);
            JSObject r = new JSObject();
            r.put("uri", id);
            call.resolve(r);
        } catch (IOException e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void abort(PluginCall call) {
        String id = call.getString("id");
        OutputStream os = open.remove(id);
        try {
            if (os != null) os.close();
        } catch (IOException ignored) {
        }
        if (id != null) getContext().getContentResolver().delete(Uri.parse(id), null, null);
        call.resolve();
    }

    /** Feuille de partage Android pour une photo déjà enregistrée. */
    @PluginMethod
    public void share(PluginCall call) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(call.getString("mime", "image/jpeg"));
        send.putExtra(Intent.EXTRA_STREAM, Uri.parse(call.getString("uri")));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getActivity().startActivity(Intent.createChooser(send, null));
        call.resolve();
    }

    /** Appelé par MainActivity : copie la photo/vidéo reçue dans le cache de l'appli, lisible par la page. */
    @SuppressWarnings("deprecation")
    void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri src = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (src == null) return;
        String mime = intent.getType();
        boolean video = mime != null && mime.startsWith("video/");
        File dst = new File(getContext().getCacheDir(), video ? "video-in.mp4" : "shared.jpg");
        new Thread(() -> {
            try {
                copy(src, dst);
            } catch (IOException | SecurityException e) { // accès refusé ou fichier illisible : on ignore, jamais de plantage
                return;
            }
            pendingShared = dst.getAbsolutePath();
            pendingType = video ? "video" : "image";
            JSObject r = new JSObject();
            r.put("path", pendingShared);
            r.put("type", pendingType);
            notifyListeners("shared", r, true); // page déjà ouverte ; retenu jusqu'à ce qu'elle écoute
        }).start();
    }

    private void copy(Uri src, File dst) throws IOException {
        try (InputStream in = getContext().getContentResolver().openInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("source illisible");
            byte[] buf = new byte[1 << 17];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
    }

    /** Démarrage à froid : la page vient de charger, elle réclame la photo reçue (une seule fois). */
    @PluginMethod
    public void getShared(PluginCall call) {
        JSObject r = new JSObject();
        r.put("path", pendingShared);
        r.put("type", pendingType);
        pendingShared = null;
        call.resolve(r);
    }

    /** Sélecteur de vidéo Android ; la vidéo est copiée dans le cache (aperçu par la page, source de l'export). */
    @PluginMethod
    public void pickVideo(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        startActivityForResult(call, i, "pickVideoResult");
    }

    @ActivityCallback
    private void pickVideoResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.resolve(new JSObject()); // annulé : pas de chemin
            return;
        }
        File dst = new File(getContext().getCacheDir(), "video-in.mp4");
        new Thread(() -> {
            try {
                copy(data.getData(), dst);
                JSObject r = new JSObject();
                r.put("path", dst.getAbsolutePath());
                call.resolve(r);
            } catch (IOException | SecurityException e) {
                call.reject(e.getMessage());
            }
        }).start();
    }

    /** Exporte la vidéo du cache avec le filtre ; progression via l'évènement « videoProgress ». */
    @PluginMethod
    public void exportVideo(PluginCall call) {
        String path = call.getString("path");
        JSObject p = call.getObject("params");
        if (path == null || p == null) {
            call.reject("paramètres manquants");
            return;
        }
        // même ordre que FilterEffect.NAMES
        String[] keys = {"exposure", "contrast", "saturation", "green", "grain", "vignette", "sharpen", "temperature", "tint",
            "shadowHue", "shadowAmt", "highlightHue", "highlightAmt", "fade", "bleach", "mono"};
        float[] params = new float[keys.length];
        for (int i = 0; i < keys.length; i++) params[i] = (float) p.optDouble(keys[i], 0);
        VideoExporter.export(getActivity(), path, call.getString("name", "emulsion.mp4"), params, new VideoExporter.Callback() {
            @Override
            public void progress(int percent) {
                JSObject r = new JSObject();
                r.put("percent", percent);
                notifyListeners("videoProgress", r);
            }

            @Override
            public void done(String uri) {
                JSObject r = new JSObject();
                r.put("uri", uri);
                call.resolve(r);
            }

            @Override
            public void fail(String message) {
                call.reject(message);
            }
        });
    }
}
