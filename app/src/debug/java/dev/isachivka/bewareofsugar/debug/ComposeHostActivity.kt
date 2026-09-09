package dev.isachivka.bewareofsugar.debug

import androidx.activity.ComponentActivity

/**
 * An empty activity for instrumentation that needs a REAL window.
 *
 * ### Why it exists
 *
 * `createComposeRule` renders into a host with no IME, and the activity's `windowSoftInputMode` never
 * applies — so screenshots from it can show a layout and say nothing whatsoever about how that layout
 * behaves with the keyboard up. That is the defect the owner reported on 2026-07-29 and the one thing
 * those screenshots could not see.
 *
 * `createAndroidComposeRule<MainActivity>` is not the answer either: MainActivity sets its own content
 * in `onCreate`, so the rule refuses to set content over the top of it.
 *
 * So this: an activity that does nothing except exist, declared in the debug manifest **with the same
 * `windowSoftInputMode` as MainActivity**, which is the property under test. Debug source set only —
 * it is not in the release manifest and cannot ship.
 */
class ComposeHostActivity : ComponentActivity()
