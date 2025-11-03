package com.rnd.ble.navigation

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rnd.ble.R
import com.rnd.ble.presentation.screens.BLEDevicesListScreen
import com.rnd.ble.presentation.screens.MainScreen
import com.rnd.ble.presentation.screens.WeightScreen
import com.rnd.ble.utility.BLEManager

@Composable
fun NavigationComponent() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val bleManager = remember { BLEManager(context) }

    val bluetoothAdapter: BluetoothAdapter? = remember {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            navController.navigate(context.getString(Screens.BLEDevicesListScreen.route))
        } else {
            Toast.makeText(context, context.getString(R.string.bluetooth_required), Toast.LENGTH_SHORT).show()
        }
    }

    NavHost(navController = navController, startDestination = context.getString(Screens.MainScreen.route)) {
        composable(route = context.getString(Screens.MainScreen.route)) { backStackEntry ->
            MainScreen(onGetStartedClicked = {
                if (bluetoothAdapter == null) {
                    Toast.makeText(context, context.getString(R.string.no_bluetooth_support), Toast.LENGTH_LONG).show()
                } else {
                    if (bluetoothAdapter.isEnabled) {
                        navController.navigate(context.getString(Screens.BLEDevicesListScreen.route))
                    } else {
                        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        enableBluetoothLauncher.launch(enableBtIntent)
                    }
                }
            })
        }

        composable(route = context.getString(Screens.BLEDevicesListScreen.route)) { backStackEntry ->
            BLEDevicesListScreen(onNavigateToWeightScreen = {
                Log.e(context.getString(R.string.tag_navigation), context.getString(R.string.navigating_to_weight_screen))
            }, bleManager = bleManager, onBack = navController::popBackStack)
        }

        composable(route = context.getString(Screens.WeightScreen.route)) { backStackEntry ->
            WeightScreen(onDisconnect = {
                Log.e(context.getString(R.string.tag_navigation), context.getString(R.string.disconnecting_and_navigating_back))
                navController.popBackStack(
                    route = context.getString(Screens.BLEDevicesListScreen.route),
                    inclusive = false
                )
            }, bleManager = bleManager
            )
        }
    }
}

sealed class Screens(val route: Int) {

    object MainScreen : Screens(R.string.main_screen_route)
    object BLEDevicesListScreen : Screens(R.string.devices_list_screen_route)
    object WeightScreen : Screens(R.string.weight_screen_route)
}
