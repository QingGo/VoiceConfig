package com.voiceconfig.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.R

@Composable
fun VoiceCommandBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceInput: () -> Unit,
    isBusy: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = onVoiceInput,
            enabled = !isBusy,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_mic),
                contentDescription = "语音输入",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("输入指令…") },
            singleLine = true,
            enabled = !isBusy,
        )
        if (isBusy) {
            Button(onClick = onStop) {
                Text("停止")
            }
        } else {
            Button(onClick = onSend, enabled = input.isNotBlank()) {
                Text("发送")
            }
        }
    }
}
