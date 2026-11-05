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
    static final int APPROVAL = 4;  // a risky action waiting for Approve / Deny

    static final int WAITING = 0;
    static final int APPROVED = 1;
    static final int DECLINED = 2;

    static final class Item {
        final int kind;
        final String text;
        /** For APPROVAL: its Approvals id and WAITING, APPROVED or DECLINED. */
        final int approvalId;
        volatile int state;

        Item(int kind, String text) {
            this(kind, text, 0);
        }

        Item(int kind, String text, int approvalId) {
            this.kind = kind;
            this.text = text;
            this.approvalId = approvalId;
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

    void addApproval(int id, String text) {
        synchronized (this) {
            mItems.add(new Item(APPROVAL, text, id));
        }
        notifyChanged();
    }

    void resolveApproval(int id, boolean yes) {
        synchronized (this) {
            for (Item i : mItems) {
                if (i.kind == APPROVAL && i.approvalId == id && i.state == WAITING) {
                    i.state = yes ? APPROVED : DECLINED;
                }
            }
        }
        notifyChanged();
    }

    /** The user's requests, newest last, for saving one as a shortcut. */
    synchronized String lastRequest() {
        for (int i = mItems.size() - 1; i >= 0; i--) {
            if (mItems.get(i).kind == USER) return mItems.get(i).text;
        }
        return null;
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
