/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.fido.core.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Job
import org.microg.gms.fido.core.R
import org.microg.gms.fido.core.RequestOptionsType
import org.microg.gms.fido.core.databinding.FidoQrCodeFragmentBinding
import org.microg.gms.fido.core.transport.Transport
import org.microg.gms.fido.core.transport.TransportHandlerCallback
import org.microg.gms.fido.core.type
import kotlin.apply
import kotlin.collections.all
import kotlin.collections.any
import kotlin.collections.filterIndexed
import kotlin.collections.getOrNull
import kotlin.collections.isNotEmpty
import kotlin.collections.toTypedArray
import kotlin.run

@RequiresApi(Build.VERSION_CODES.N)
class QrCodeFragment : Fragment(), TransportHandlerCallback {

    companion object {
        private const val TAG = "QrCodeFragment"
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1001
        private const val STATE_AWAITING_SETTINGS_RETURN = "awaitingSettingsReturn"
        private const val STATE_PERMISSION_REQUEST_IN_FLIGHT = "permissionRequestInFlight"
    }

    private lateinit var binding: FidoQrCodeFragmentBinding
    private lateinit var activityHost: AuthenticatorActivity
    private var transportJob: Job? = null
    private var awaitingSettingsReturn = false
    private var permissionRequestInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingSettingsReturn = savedInstanceState?.getBoolean(STATE_AWAITING_SETTINGS_RETURN, false) == true
        permissionRequestInFlight = savedInstanceState?.getBoolean(STATE_PERMISSION_REQUEST_IN_FLIGHT, false) == true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AWAITING_SETTINGS_RETURN, awaitingSettingsReturn)
        outState.putBoolean(STATE_PERMISSION_REQUEST_IN_FLIGHT, permissionRequestInFlight)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        activityHost = requireActivity() as AuthenticatorActivity
        binding = DataBindingUtil.inflate(inflater, R.layout.fido_qr_code_fragment, container, false)

        binding.data = AuthenticatorActivityFragmentData(requireArguments())

        binding.root.findViewById<Button>(R.id.cancel_button).setOnClickListener {
            activityHost.finish()
        }

        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBodyText()

        if (!awaitingSettingsReturn && !permissionRequestInFlight && requestBluetoothPermissions()) {
            ensureTransportStarted()
        }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingSettingsReturn) {
            awaitingSettingsReturn = false
            if (missingBluetoothPermissions().isEmpty()) {
                ensureTransportStarted()
            } else {
                Toast.makeText(
                    requireContext(), getString(R.string.fido_ble_permission_blocked_toast), Toast.LENGTH_SHORT
                ).show()
                activityHost.finish()
            }
        }
    }

    override fun onDestroyView() {
        transportJob?.cancel()
        transportJob = null
        super.onDestroyView()
    }

    private fun ensureTransportStarted() {
        if (!isAdded || transportJob?.isActive == true) return
        transportJob = activityHost.startTransportHandling(Transport.HYBRID)
    }

    private fun setupBodyText() {
        val bodyTextView = view?.findViewById<TextView>(R.id.qr_code_body_text) ?: return
        val operationType = activityHost.options?.type
        bodyTextView.text = when (operationType) {
            RequestOptionsType.REGISTER -> getString(R.string.fido_qr_code_body_register)
            RequestOptionsType.SIGN -> getString(R.string.fido_qr_code_body_sign)
            else -> getString(R.string.fido_qr_code_body_sign)
        }
        Log.d(TAG, "Setup body text for operation type: $operationType")
    }

    override fun onStatusChanged(transport: Transport, status: String, extras: Bundle?) {
        if (transport != Transport.HYBRID) return
        when (status) {
            "QR_CODE_READY" -> {
                activityHost.runOnUiThread {
                    val qrImageView = view?.findViewById<ImageView>(R.id.qr_code_image)
                    val progressBar = view?.findViewById<ProgressBar>(R.id.qr_progress)
                    if (qrImageView != null) {
                        qrImageView.setImageBitmap(extras?.getParcelable("qrCodeBitmap"))
                        qrImageView.visibility = View.VISIBLE
                        progressBar?.visibility = View.GONE
                    }
                }
            }

            "CONNECTING" -> {
                activityHost.runOnUiThread {
                    val statusTextView = view?.findViewById<TextView>(R.id.qr_status_text)
                    val progressBar = view?.findViewById<ProgressBar>(R.id.qr_progress)
                    statusTextView?.text = getString(R.string.fido_ble_connecting)
                    progressBar?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun missingBluetoothPermissions(): List<String> {
        val ctx = context ?: return emptyList()
        val requiredPermissions = mutableListOf<String>()
        if (SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        } else {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return requiredPermissions
    }

    private fun requestBluetoothPermissions(): Boolean {
        val act = activity ?: run {
            Log.w(TAG, "Activity is null, cannot request permissions")
            return false
        }
        if (!isAdded || act.isFinishing || act.isDestroyed) {
            Log.w(TAG, "Fragment not added or Activity finishing/destroyed, cannot request permissions")
            return false
        }

        val requiredPermissions = missingBluetoothPermissions()

        return if (requiredPermissions.isNotEmpty()) {
            try {
                permissionRequestInFlight = true
                requestPermissions(
                    requiredPermissions.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS
                )
            } catch (_: Exception) {
                permissionRequestInFlight = false
                Log.e(TAG, "requestPermissions failed in Fragment")
                false
            }
            false
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_BLUETOOTH_PERMISSIONS -> {
                permissionRequestInFlight = false
                val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    ensureTransportStarted()
                } else {
                    val deniedPermissions = permissions.filterIndexed { index, _ ->
                        grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED
                    }
                    Log.w(TAG, "Bluetooth permissions denied: $deniedPermissions")

                    val permanentlyDenied = deniedPermissions.any { permission ->
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            requireActivity(), permission
                        )
                    }

                    if (permanentlyDenied) {
                        Log.w(TAG, "Some permissions were permanently denied")
                        showPermissionSettingsDialog()
                    } else {
                        Log.w(TAG, "Permissions denied but can be requested again")
                        Toast.makeText(
                            requireContext(), getString(R.string.fido_ble_permission_rationale), Toast.LENGTH_SHORT
                        ).show()
                        activityHost.finish()
                    }
                }
            }
        }
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(requireContext()).setTitle(getString(R.string.fido_ble_permission_title)).setMessage(getString(R.string.fido_ble_permission_permanently_denied))
            .setPositiveButton(getString(R.string.fido_action_go_to_settings)) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", requireContext().packageName, null)
                    }
                    awaitingSettingsReturn = true
                    startActivity(intent)
                } catch (e: Exception) {
                    awaitingSettingsReturn = false
                    Log.e(TAG, "Failed to open app settings", e)
                    Toast.makeText(
                        requireContext(), getString(R.string.fido_ble_open_settings_failed), Toast.LENGTH_LONG
                    ).show()
                    activityHost.finish()
                }
            }.setNegativeButton(getString(R.string.fido_pin_cancel)) { dialog, _ ->
                dialog.dismiss()
                if (isAdded) {
                    Toast.makeText(
                        requireContext(), getString(R.string.fido_ble_permission_blocked_toast), Toast.LENGTH_SHORT
                    ).show()
                    activityHost.finish()
                }
            }.setCancelable(false).show()
    }
}
