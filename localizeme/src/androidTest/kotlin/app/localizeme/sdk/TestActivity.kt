package app.localizeme.sdk

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.localizeme.sdk.test.R

/** The host app of the instrumented tests: one wrapped AppCompat activity with an XML layout. */
class TestActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocalizeMe.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.lz_test)
    }
}
