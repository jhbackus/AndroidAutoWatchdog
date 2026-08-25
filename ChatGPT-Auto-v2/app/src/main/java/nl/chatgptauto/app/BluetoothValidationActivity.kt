package nl.chatgptauto.app

import android.app.Activity

/**
 * Android 16/17 media browse validation hook required for legacy
 * MediaBrowserService-based apps. The activity has no UI and is only
 * discoverable through the narrowly-scoped content://media audio intent.
 */
class BluetoothValidationActivity : Activity()
