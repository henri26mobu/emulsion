package dev.rangermobu.emulsion;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/** Décode → filtre GPU → encode H.264 (son conservé), puis range le MP4 dans Movies/Emulsion. */
final class VideoExporter {
    interface Callback {
        void progress(int percent);
        void done(String uri);
        void fail(String message);
    }

    static void export(Activity act, String inPath, String name, float[] params, Callback cb) {
        Context ctx = act.getApplicationContext();
        File out = new File(ctx.getCacheDir(), "export.mp4");
        out.delete();
        // Transformer veut un thread avec Looper : le thread principal
        act.runOnUiThread(() -> {
            AtomicBoolean finished = new AtomicBoolean(false);
            Handler handler = new Handler(Looper.getMainLooper());
            Transformer transformer;
            try {
                transformer = new Transformer.Builder(ctx).addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition c, ExportResult r) {
                        finished.set(true);
                        new Thread(() -> { // copie lourde : hors thread principal
                            try {
                                cb.done(toGallery(ctx, out, name));
                            } catch (Exception e) {
                                cb.fail(String.valueOf(e.getMessage()));
                            } finally {
                                out.delete();
                            }
                        }).start();
                    }

                    @Override
                    public void onError(Composition c, ExportResult r, ExportException e) {
                        finished.set(true);
                        out.delete();
                        cb.fail("Export impossible : " + e.getMessage());
                    }
                }).build();
                EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(new File(inPath))))
                    .setEffects(new Effects(
                        Collections.<AudioProcessor>emptyList(),
                        Collections.<Effect>singletonList(new FilterEffect(params))))
                    .build();
                transformer.start(item, out.getAbsolutePath());
            } catch (RuntimeException e) {
                cb.fail("Export impossible : " + e.getMessage());
                return;
            }
            ProgressHolder holder = new ProgressHolder();
            Transformer t = transformer;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (finished.get()) return;
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) cb.progress(holder.progress);
                    handler.postDelayed(this, 500);
                }
            });
        });
    }

    private static String toGallery(Context ctx, File src, String name) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name.replaceAll("[^A-Za-z0-9._-]", "_"));
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Emulsion");
        v.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("MediaStore a refusé la création du fichier");
        try (InputStream in = new FileInputStream(src); OutputStream os = cr.openOutputStream(uri)) {
            byte[] buf = new byte[1 << 17];
            for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Video.Media.IS_PENDING, 0); // visible dans la Galerie
        cr.update(uri, done, null, null);
        return uri.toString();
    }
}
