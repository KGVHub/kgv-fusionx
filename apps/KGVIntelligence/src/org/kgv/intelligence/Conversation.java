package org.kgv.intelligence;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/** What the chat shows, kept for the life of the process so reopening keeps the history. */
final class Conversation {
    static final int USER = 0;
    static final int ASSISTANT = 1;
    static final int STEP = 2;      // an automation step, shown small
    static final int ERROR = 3;

    static final class Item {
        final int kind;
        final String text;

        Item(int kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    interface Listener {
        void onChanged();
    }

    private static final Conversation sInstance = new Conversation();

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Item> mItems = new ArrayList<>();
    private final List<Listener> mListeners = new ArrayList<>();
    private volatile boolean mBusy;

    static Conversation get() {
        return sInstance;
    }

    synchronized List<Item> items() {
        return new ArrayList<>(mItems);
    }

    void add(int kind, String text) {
        synchronized (this) {
            mItems.add(new Item(kind, text));
        }
        notifyChanged();
    }

    void clear() {
        synchronized (this) {
            mItems.clear();
        }
        notifyChanged();
    }

    boolean isBusy() {
        return mBusy;
    }

    void setBusy(boolean busy) {
        mBusy = busy;
        notifyChanged();
    }

    void addListener(Listener l) {
        mListeners.add(l);
    }

    void removeListener(Listener l) {
        mListeners.remove(l);
    }

    private void notifyChanged() {
        mMain.post(() -> {
            for (Listener l : new ArrayList<>(mListeners)) l.onChanged();
        });
    }
}
