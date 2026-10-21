package com.repcoach.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import com.repcoach.phone.net.FoundTv

private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

fun isValidIpv4(text: String): Boolean =
    IPV4.matchEntire(text.trim())?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true

@Composable
fun ConnectScreen(tvs: List<FoundTv>, lastIp: String, onConnect: (String) -> Unit) {
    var ip by rememberSaveable { mutableStateOf(lastIp) }
    val valid = isValidIpv4(ip)
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Connect to your TV", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Open RepCoach on the Fire TV. Phone and TV must be on the same Wi-Fi.",
                style = MaterialTheme.typography.bodyLarge,
            )

            if (tvs.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Looking for TVs...", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                tvs.forEach { tv ->
                    OutlinedButton(onClick = { onConnect(tv.host) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${tv.name} (${tv.host})")
                    }
                }
            }

            HorizontalDivider()
            Text("Or type the IP shown on the TV", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it.trim() },
                label = { Text("TV IP address") },
                placeholder = { Text("192.168.1.20") },
                singleLine = true,
                isError = ip.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onConnect(ip) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onConnect(ip) }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
                Text("Connect")
            }
        }
    }
}
