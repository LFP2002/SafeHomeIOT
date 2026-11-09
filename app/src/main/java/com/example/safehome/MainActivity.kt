package com.example.safehome

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.example.safehome.ui.theme.SafeHomeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controlSesion = ViewModelProvider(this)[ControlSesion::class.java]

        setContent {
            SafeHomeTheme {
                SafeHomeApp(controlSesion)
            }
        }
    }
}
