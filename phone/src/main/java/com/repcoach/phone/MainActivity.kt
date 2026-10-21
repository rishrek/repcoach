package com.repcoach.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repcoach.phone.net.Summarizer
import com.repcoach.phone.net.TvDiscovery
import com.repcoach.phone.pose.PoseSession
import com.repcoach.phone.ui.ConnectScreen
import com.repcoach.phone.ui.SessionScreen

class MainActivity : ComponentActivity() {
    private lateinit var session: PoseSession
    private lateinit var discovery: TvDiscovery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = PoseSession(this, Summarizer(BuildConfig.LAMBDA_URL, BuildConfig.LAMBDA_KEY))
        val link = session.link
        discovery = TvDiscovery(this)
        val prefs = getSharedPreferences("repcoach", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                // null means we show the connect screen
                var host by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(host) { host?.let(link::connect) ?: link.disconnect() }

                val current = host
                if (current == null) {
                    DisposableEffect(Unit) {
                        discovery.start()
                        onDispose { discovery.stop() }
                    }
                    val tvs by discovery.tvs.collectAsStateWithLifecycle()
                    ConnectScreen(tvs, prefs.getString(LAST_IP, "").orEmpty()) { ip ->
                        prefs.edit { putString(LAST_IP, ip) }
                        host = ip
                    }
                } else {
                    BackHandler { host = null }
                    CameraPermissionGate {
                        val state by session.state.collectAsStateWithLifecycle()
                        val linkState by link.state.collectAsStateWithLifecycle()
                        SessionScreen(
                            state = state,
                            link = linkState,
                            onFrame = session.tracker::detect,
                            onExercise = session::selectExercise,
                            onRecordingChange = session::setRecording,
                            onChangeTv = { host = null },
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // don't leave a csv open when the app goes to the background
        session.setRecording(false)
    }

    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }

    private companion object {
        const val LAST_IP = "last_ip"
    }
}

@Composable
private fun CameraPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (granted) {
        content()
    } else {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("RepCoach needs the camera to see your squats. Video never leaves this phone.", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
    }
}
