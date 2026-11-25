package com.omnibox.app.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.omnibox.app.TvMode
import com.omnibox.app.ui.theme.OmniBoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvMode.init()
        requestedOrientation = TvMode.orientation()
        setContent {
            OmniBoxTheme {
                OmniBoxTvApp()
            }
        }
    }
}
