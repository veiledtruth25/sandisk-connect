package id.abimanyu.stickconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import id.abimanyu.stickconnect.ui.App
import id.abimanyu.stickconnect.ui.StickTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StickTheme {
                App()
            }
        }
    }
}
