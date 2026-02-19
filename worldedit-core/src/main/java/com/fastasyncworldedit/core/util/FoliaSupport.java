package com.fastasyncworldedit.core.util;

public final class FoliaSupport {
    private FoliaSupport() {

    }

    private static final boolean IS_FOLIA;
    private static final boolean HAS_TICK_THREAD;
    static {
        boolean isFolia = false;
        try {
            // Assume implementation details are present
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
        } catch (Exception unused) {

        }
        IS_FOLIA = isFolia;
        boolean hasTickThread = false;
        if (IS_FOLIA) {
            try {
                // Try different possible TickThread class names for different Folia versions
                Class.forName("io.papermc.paper.util.TickThread");
                hasTickThread = true;
            } catch (ClassNotFoundException e1) {
                try {
                    Class.forName("ca.spottedleaf.moonrise.common.util.TickThread");
                    hasTickThread = true;
                } catch (ClassNotFoundException e2) {
                    try {
                        Class.forName("io.papermc.paper.threadedregions.TickThread");
                        hasTickThread = true;
                    } catch (ClassNotFoundException e3) {
                        // Fallback: no TickThread available in this Folia version
                        hasTickThread = false;
                    }
                }
            }
        }
        HAS_TICK_THREAD = hasTickThread;
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    public static boolean isTickThread() {
        if (!IS_FOLIA) {
            return true; // Non-Folia servers have main thread concept
        }
        if (!HAS_TICK_THREAD) {
            return true; // Fallback for Folia versions without TickThread
        }
        try {
            // Try to check if current thread is a tick thread
            Class<?> tickThreadClass = Class.forName("io.papermc.paper.util.TickThread");
            return tickThreadClass.isInstance(Thread.currentThread());
        } catch (ClassNotFoundException e1) {
            try {
                Class<?> tickThreadClass = Class.forName("ca.spottedleaf.moonrise.common.util.TickThread");
                return tickThreadClass.isInstance(Thread.currentThread());
            } catch (ClassNotFoundException e2) {
                try {
                    Class<?> tickThreadClass = Class.forName("io.papermc.paper.threadedregions.TickThread");
                    return tickThreadClass.isInstance(Thread.currentThread());
                } catch (ClassNotFoundException e3) {
                    return true; // Fallback: assume we're on a tick thread
                }
            }
        }
    }


    @FunctionalInterface
    public interface ThrowingSupplier<T> {

        T get() throws Throwable;

    }
    @FunctionalInterface
    public interface ThrowingRunnable {

        void run() throws Throwable;

    }

    public static void runRethrowing(ThrowingRunnable runnable) {
        getRethrowing(() -> {
            runnable.run();
            return null;
        });
    }

    public static <T> T getRethrowing(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
