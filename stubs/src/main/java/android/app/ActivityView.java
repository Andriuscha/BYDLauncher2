package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.graphics.Insets;
import android.os.UserHandle;
import android.view.View;

/**
 * Compile-only stub для android.app.ActivityView (@TestApi).
 * В APK НЕ попадает — используется только при компиляции Kotlin-кода.
 * В рантайме применяется системный класс из framework.jar.
 */
public class ActivityView extends View {

    public ActivityView(Context context) {
        super(context);
        throw new RuntimeException("Stub!");
    }

    public ActivityView(Context context, boolean throwOnAttachError) {
        super(context);
        throw new RuntimeException("Stub!");
    }

    public void setCallback(StateCallback callback) {
        throw new RuntimeException("Stub!");
    }

    public void setSurfaceCallback(SurfaceCallback callback) {
        throw new RuntimeException("Stub!");
    }

    public void setForwardedInsets(Insets insets) {
        throw new RuntimeException("Stub!");
    }

    public void setCornerRadius(float radius) {
        throw new RuntimeException("Stub!");
    }

    public int getVirtualDisplayId() {
        throw new RuntimeException("Stub!");
    }

    public void startActivity(Intent intent) {
        throw new RuntimeException("Stub!");
    }

    public void startActivity(Intent intent, UserHandle userHandle) {
        throw new RuntimeException("Stub!");
    }

    public boolean performBackPress() {
        throw new RuntimeException("Stub!");
    }

    public void release() {
        throw new RuntimeException("Stub!");
    }

    public void onLocationChanged() {
        throw new RuntimeException("Stub!");
    }

    public static abstract class StateCallback {
        public StateCallback() {
            throw new RuntimeException("Stub!");
        }

        public abstract void onActivityViewReady(ActivityView view);
        public abstract void onActivityViewDestroyed(ActivityView view);
        public abstract void onTaskCreated(int taskId, ComponentName componentName);
        public abstract void onTaskRemoved(int taskId);
    }

    public static abstract class SurfaceCallback {
        public SurfaceCallback() {
            throw new RuntimeException("Stub!");
        }
    }

    public static class TaskStackListenerImpl {
        public TaskStackListenerImpl() {
            throw new RuntimeException("Stub!");
        }
    }
}