package org.kgv.browser;

/**
 * KGV Browser's control interface for KGV X Intelligence: one JSON command per call,
 * answered with a JSON result (see org.kgv.browser.Commands). Only apps signed with the
 * platform key may bind (org.kgv.browser.permission.CONTROL).
 */
interface IKgvBrowser {
    String call(String request);
}
