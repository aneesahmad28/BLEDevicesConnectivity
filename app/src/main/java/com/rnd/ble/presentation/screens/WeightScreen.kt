package com.rnd.ble.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rnd.ble.R
import com.rnd.ble.utility.BLEManager

@Composable
fun WeightScreen( onDisconnect: () -> Unit, bleManager: BLEManager) {

    val receivedData by bleManager.receivedData.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.connected_to_weight_machine),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.padding(16.dp))

        Text(
            text = receivedData,
            style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        )

        Spacer(modifier = Modifier.padding(16.dp))

        Button(onClick = {
            bleManager.disconnect()
        onDisconnect.invoke()
        }
        ) {
            Text(stringResource(R.string.disconnect))
        }
}
}
