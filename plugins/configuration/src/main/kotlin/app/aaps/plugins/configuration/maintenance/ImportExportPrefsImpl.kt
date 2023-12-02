package app.aaps.plugins.configuration.maintenance

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.UE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.androidPermissions.AndroidPermission
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.maintenance.PrefFileListProvider
import app.aaps.core.interfaces.maintenance.PrefMetadata
import app.aaps.core.interfaces.maintenance.PrefsFile
import app.aaps.core.interfaces.maintenance.PrefsMetadataKey
import app.aaps.core.interfaces.protection.PasswordCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.rx.events.EventDiaconnG8PumpLogReset
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.storage.Storage
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.userEntry.UserEntryPresentationHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.Preferences
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.dialogs.TwoMessagesAlertDialog
import app.aaps.core.ui.dialogs.WarningDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.plugins.configuration.R
import app.aaps.plugins.configuration.activities.DaggerAppCompatActivityWithResult
import app.aaps.plugins.configuration.maintenance.data.PrefFileNotFoundError
import app.aaps.plugins.configuration.maintenance.data.PrefIOError
import app.aaps.plugins.configuration.maintenance.data.Prefs
import app.aaps.plugins.configuration.maintenance.data.PrefsFormat
import app.aaps.plugins.configuration.maintenance.data.PrefsStatusImpl
import app.aaps.plugins.configuration.maintenance.dialogs.PrefImportSummaryDialog
import app.aaps.plugins.configuration.maintenance.formats.EncryptedPrefsFormat
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import dagger.Reusable
import dagger.android.HasAndroidInjector
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * Created by mike on 03.07.2016.
 */

@Reusable
class ImportExportPrefsImpl @Inject constructor(
    private var log: AAPSLogger,
    private val rh: ResourceHelper,
    private val sp: SP,
    private val preferences: Preferences,
    private val config: Config,
    private val rxBus: RxBus,
    private val passwordCheck: PasswordCheck,
    private val androidPermission: AndroidPermission,
    private val encryptedPrefsFormat: EncryptedPrefsFormat,
    private val prefFileList: PrefFileListProvider,
    private val uel: UserEntryLogger,
    private val dateUtil: DateUtil,
    private val uiInteraction: UiInteraction,
    private val context: Context,
    private val dataWorkerStorage: DataWorkerStorage
) : ImportExportPrefs {

    override fun prefsFileExists(): Boolean {
        return prefFileList.listPreferenceFiles().size > 0
    }

    override fun exportSharedPreferences(f: Fragment) {
        f.activity?.let { exportSharedPreferences(it) }
    }

    override fun verifyStoragePermissions(fragment: Fragment, onGranted: Runnable) {
        fragment.context?.let { ctx ->
            val permission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (permission != PackageManager.PERMISSION_GRANTED) {
                // We don't have permission so prompt the user
                fragment.activity?.let {
                    androidPermission.askForPermission(it, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                }
            } else onGranted.run()
        }
    }

    private fun prepareMetadata(context: Context): Map<PrefsMetadataKey, PrefMetadata> {

        val metadata: MutableMap<PrefsMetadataKey, PrefMetadata> = mutableMapOf()

        metadata[PrefsMetadataKeyImpl.DEVICE_NAME] = PrefMetadata(detectUserName(context), PrefsStatusImpl.OK)
        metadata[PrefsMetadataKeyImpl.CREATED_AT] = PrefMetadata(dateUtil.toISOString(dateUtil.now()), PrefsStatusImpl.OK)
        metadata[PrefsMetadataKeyImpl.AAPS_VERSION] = PrefMetadata(config.VERSION_NAME, PrefsStatusImpl.OK)
        metadata[PrefsMetadataKeyImpl.AAPS_FLAVOUR] = PrefMetadata(config.FLAVOR, PrefsStatusImpl.OK)
        metadata[PrefsMetadataKeyImpl.DEVICE_MODEL] = PrefMetadata(config.currentDeviceModelString, PrefsStatusImpl.OK)
        metadata[PrefsMetadataKeyImpl.ENCRYPTION] = PrefMetadata("Enabled", PrefsStatusImpl.OK)

        return metadata
    }

    @Suppress("SpellCheckingInspection")
    private fun detectUserName(context: Context): String {
        // based on https://medium.com/@pribble88/how-to-get-an-android-device-nickname-4b4700b3068c
        val n1 = Settings.System.getString(context.contentResolver, "bluetooth_name")
        val n2 = Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        val n3 = try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter?.name
            } else null
        } catch (e: Exception) {
            null
        }
        val n4 = Settings.System.getString(context.contentResolver, "device_name")
        val n5 = Settings.Secure.getString(context.contentResolver, "lock_screen_owner_info")
        val n6 = Settings.Global.getString(context.contentResolver, "device_name")

        // name provided (hopefully) by user
        val patientName = sp.getString(app.aaps.core.utils.R.string.key_patient_name, "")
        val defaultPatientName = rh.gs(app.aaps.core.ui.R.string.patient_name_default)

        // name we detect from OS
        val systemName = n1 ?: n2 ?: n3 ?: n4 ?: n5 ?: n6 ?: defaultPatientName
        return if (patientName.isNotEmpty() && patientName != defaultPatientName) patientName else systemName
    }

    private fun askForMasterPass(activity: FragmentActivity, @StringRes canceledMsg: Int, then: ((password: String) -> Unit)) {
        passwordCheck.queryPassword(activity, app.aaps.core.ui.R.string.master_password, app.aaps.core.utils.R.string.key_master_password, { password ->
            then(password)
        }, {
                                        ToastUtils.warnToast(activity, rh.gs(canceledMsg))
                                    })
    }

    @Suppress("SameParameterValue")
    private fun askForEncryptionPass(
        activity: FragmentActivity, @StringRes canceledMsg: Int, @StringRes passwordName: Int, @StringRes passwordExplanation: Int?,
        @StringRes passwordWarning: Int?, then: ((password: String) -> Unit)
    ) {
        passwordCheck.queryAnyPassword(activity, passwordName, app.aaps.core.utils.R.string.key_master_password, passwordExplanation, passwordWarning, { password ->
            then(password)
        }, {
                                           ToastUtils.warnToast(activity, rh.gs(canceledMsg))
                                       })
    }

    @Suppress("SameParameterValue")
    private fun askForMasterPassIfNeeded(activity: FragmentActivity, @StringRes canceledMsg: Int, then: ((password: String) -> Unit)) {
        askForMasterPass(activity, canceledMsg, then)
    }

    private fun assureMasterPasswordSet(activity: FragmentActivity, @StringRes wrongPwdTitle: Int): Boolean {
        if (!sp.contains(app.aaps.core.utils.R.string.key_master_password) || (sp.getString(app.aaps.core.utils.R.string.key_master_password, "") == "")) {
            WarningDialog.showWarning(activity,
                                      rh.gs(wrongPwdTitle),
                                      rh.gs(R.string.master_password_missing, rh.gs(R.string.configbuilder_general), rh.gs(R.string.protection)),
                                      R.string.nav_preferences, {
                                          val intent = Intent(activity, uiInteraction.preferencesActivity).apply {
                                              putExtra("id", uiInteraction.prefGeneral)
                                          }
                                          activity.startActivity(intent)
                                      })
            return false
        }
        return true
    }

    private fun askToConfirmExport(activity: FragmentActivity, fileToExport: File, then: ((password: String) -> Unit)) {
        if (!assureMasterPasswordSet(activity, R.string.nav_export)) return

        TwoMessagesAlertDialog.showAlert(
            activity, rh.gs(R.string.nav_export),
            rh.gs(R.string.export_to) + " " + fileToExport.name + " ?",
            rh.gs(R.string.password_preferences_encrypt_prompt), {
                askForMasterPassIfNeeded(activity, R.string.preferences_export_canceled, then)
            }, null, R.drawable.ic_header_export
        )
    }

    private fun askToConfirmImport(activity: FragmentActivity, fileToImport: PrefsFile, then: ((password: String) -> Unit)) {
        if (!assureMasterPasswordSet(activity, R.string.import_setting)) return
        TwoMessagesAlertDialog.showAlert(
            activity, rh.gs(R.string.import_setting),
            rh.gs(R.string.import_from) + " " + fileToImport.name + " ?",
            rh.gs(app.aaps.core.ui.R.string.password_preferences_decrypt_prompt), {
                askForMasterPass(activity, R.string.preferences_import_canceled, then)
            }, null, R.drawable.ic_header_import
        )
    }

    private fun promptForDecryptionPasswordIfNeeded(
        activity: FragmentActivity, prefs: Prefs, importOk: Boolean,
        format: PrefsFormat, importFile: PrefsFile, then: ((prefs: Prefs, importOk: Boolean) -> Unit)
    ) {

        // current master password was not the one used for decryption, so we prompt for old password...
        if (!importOk && (prefs.metadata[PrefsMetadataKeyImpl.ENCRYPTION]?.status == PrefsStatusImpl.ERROR)) {
            askForEncryptionPass(
                activity, R.string.preferences_import_canceled, R.string.old_master_password,
                R.string.different_password_used, R.string.master_password_will_be_replaced
            ) { password ->

                // ...and use it to load & decrypt file again
                val prefsReloaded = format.loadPreferences(importFile.file, password)
                prefsReloaded.metadata = prefFileList.checkMetadata(prefsReloaded.metadata)

                // import is OK when we do not have errors (warnings are allowed)
                val importOkCheckedAgain = checkIfImportIsOk(prefsReloaded)

                then(prefsReloaded, importOkCheckedAgain)
            }
        } else {
            then(prefs, importOk)
        }
    }

    private fun exportSharedPreferences(activity: FragmentActivity) {

        prefFileList.ensureExportDirExists()
        val newFile = prefFileList.newExportFile()

        askToConfirmExport(activity, newFile) { password ->
            try {
                val entries: MutableMap<String, String> = mutableMapOf()
                for ((key, value) in sp.getAll()) {
                    entries[key] = value.toString()
                }

                val prefs = Prefs(entries, prepareMetadata(activity))

                encryptedPrefsFormat.savePreferences(newFile, prefs, password)

                ToastUtils.okToast(activity, rh.gs(R.string.exported))
            } catch (e: FileNotFoundException) {
                ToastUtils.errorToast(activity, rh.gs(R.string.filenotfound) + " " + newFile)
                log.error(LTag.CORE, "Unhandled exception", e)
            } catch (e: IOException) {
                ToastUtils.errorToast(activity, e.message)
                log.error(LTag.CORE, "Unhandled exception", e)
            } catch (e: PrefFileNotFoundError) {
                ToastUtils.Long.errorToast(
                    activity, rh.gs(R.string.preferences_export_canceled)
                        + "\n\n" + rh.gs(R.string.filenotfound)
                        + ": " + e.message
                        + "\n\n" + rh.gs(R.string.need_storage_permission)
                )
                log.error(LTag.CORE, "File system exception", e)
            } catch (e: PrefIOError) {
                ToastUtils.Long.errorToast(
                    activity, rh.gs(R.string.preferences_export_canceled)
                        + "\n\n" + rh.gs(R.string.need_storage_permission)
                        + ": " + e.message
                )
                log.error(LTag.CORE, "File system exception", e)
            }
        }
    }

    override fun importSharedPreferences(fragment: Fragment) {
        fragment.activity?.let { fragmentAct ->
            importSharedPreferences(fragmentAct)
        }
    }

    override fun importSharedPreferences(activity: FragmentActivity) {

        try {
            if (activity is DaggerAppCompatActivityWithResult)
                activity.callForPrefFile.launch(null)
        } catch (e: IllegalArgumentException) {
            // this exception happens on some early implementations of ActivityResult contracts
            // when registered and called for the second time
            ToastUtils.errorToast(activity, rh.gs(R.string.goto_main_try_again))
            log.error(LTag.CORE, "Internal android framework exception", e)
        }
    }

    override fun importCustomWatchface(fragment: Fragment) {
        fragment.activity?.let { importCustomWatchface(it) }
    }

    override fun importCustomWatchface(activity: FragmentActivity) {
        try {
            if (activity is DaggerAppCompatActivityWithResult)
                activity.callForCustomWatchfaceFile.launch(null)
        } catch (e: IllegalArgumentException) {
            // this exception happens on some early implementations of ActivityResult contracts
            // when registered and called for the second time
            ToastUtils.errorToast(activity, rh.gs(R.string.goto_main_try_again))
            log.error(LTag.CORE, "Internal android framework exception", e)
        }
    }

    override fun exportCustomWatchface(customWatchface: CwfData, withDate: Boolean) {
        prefFileList.ensureExportDirExists()
        val newFile = prefFileList.newCwfFile(customWatchface.metadata[CwfMetadataKey.CWF_FILENAME] ?: "", withDate)
        ZipWatchfaceFormat.saveCustomWatchface(newFile, customWatchface)
    }

    override fun importSharedPreferences(activity: FragmentActivity, importFile: PrefsFile) {

        askToConfirmImport(activity, importFile) { password ->

            val format: PrefsFormat = encryptedPrefsFormat

            try {

                val prefsAttempted = format.loadPreferences(importFile.file, password)
                prefsAttempted.metadata = prefFileList.checkMetadata(prefsAttempted.metadata)

                // import is OK when we do not have errors (warnings are allowed)
                val importOkAttempted = checkIfImportIsOk(prefsAttempted)

                promptForDecryptionPasswordIfNeeded(activity, prefsAttempted, importOkAttempted, format, importFile) { prefs, importOk ->

                    // if at end we allow to import preferences
                    val importPossible = (importOk || config.isEngineeringMode()) && (prefs.values.isNotEmpty())

                    PrefImportSummaryDialog.showSummary(activity, importOk, importPossible, prefs, {
                        if (importPossible) {
                            sp.clear()
                            for ((key, value) in prefs.values) {
                                if (value == "true" || value == "false") {
                                    sp.putBoolean(key, value.toBoolean())
                                } else {
                                    sp.putString(key, value)
                                }
                            }

                            restartAppAfterImport(activity)
                        } else {
                            // for impossible imports it should not be called
                            ToastUtils.errorToast(activity, rh.gs(R.string.preferences_import_impossible))
                        }
                    })

                }

            } catch (e: PrefFileNotFoundError) {
                ToastUtils.errorToast(activity, rh.gs(R.string.filenotfound) + " " + importFile)
                log.error(LTag.CORE, "Unhandled exception", e)
            } catch (e: PrefIOError) {
                log.error(LTag.CORE, "Unhandled exception", e)
                ToastUtils.errorToast(activity, e.message)
            }
        }
    }

    private fun checkIfImportIsOk(prefs: Prefs): Boolean {
        var importOk = true

        for ((_, value) in prefs.metadata) {
            if (value.status == PrefsStatusImpl.ERROR)
                importOk = false
        }
        return importOk
    }

    private fun restartAppAfterImport(context: Context) {
        rxBus.send(EventDiaconnG8PumpLogReset())
        preferences.put(BooleanKey.GeneralSetupWizardProcessed, true)
        OKDialog.show(context, rh.gs(R.string.setting_imported), rh.gs(R.string.restartingapp)) {
            uel.log(Action.IMPORT_SETTINGS, Sources.Maintenance)
            log.debug(LTag.CORE, "Exiting")
            rxBus.send(EventAppExit())
            if (context is AppCompatActivity) {
                context.finish()
            }
            System.runFinalization()
            exitProcess(0)
        }
    }

    override fun exportUserEntriesCsv(activity: FragmentActivity) {
        WorkManager.getInstance(activity).enqueueUniqueWork(
            "export",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequest.Builder(CsvExportWorker::class.java).build()
        )
    }

    class CsvExportWorker(
        context: Context,
        params: WorkerParameters
    ) : LoggingWorker(context, params, Dispatchers.IO) {

        @Inject lateinit var injector: HasAndroidInjector
        @Inject lateinit var rh: ResourceHelper
        @Inject lateinit var prefFileList: PrefFileListProvider
        @Inject lateinit var context: Context
        @Inject lateinit var userEntryPresentationHelper: UserEntryPresentationHelper
        @Inject lateinit var storage: Storage
        @Inject lateinit var persistenceLayer: PersistenceLayer

        override suspend fun doWorkAndLog(): Result {
            val entries = persistenceLayer.getUserEntryFilteredDataFromTime(MidnightTime.calc() - T.days(90).msecs()).blockingGet()
            prefFileList.ensureExportDirExists()
            val newFile = prefFileList.newExportCsvFile()
            var ret = Result.success()
            try {
                saveCsv(newFile, entries)
                ToastUtils.okToast(context, rh.gs(R.string.ue_exported))
            } catch (e: FileNotFoundException) {
                ToastUtils.errorToast(context, rh.gs(R.string.filenotfound) + " " + newFile)
                aapsLogger.error(LTag.CORE, "Unhandled exception", e)
                ret = Result.failure(workDataOf("Error" to "Error FileNotFoundException"))
            } catch (e: IOException) {
                ToastUtils.errorToast(context, e.message)
                aapsLogger.error(LTag.CORE, "Unhandled exception", e)
                ret = Result.failure(workDataOf("Error" to "Error IOException"))
            }
            return ret
        }

        private fun saveCsv(file: File, userEntries: List<UE>) {
            try {
                val contents = userEntryPresentationHelper.userEntriesToCsv(userEntries)
                storage.putFileContents(file, contents)
            } catch (e: FileNotFoundException) {
                throw PrefFileNotFoundError(file.absolutePath)
            } catch (e: IOException) {
                throw PrefIOError(file.absolutePath)
            }
        }
    }

    override fun exportApsResult(algorithm: String?, input: JSONObject, output: JSONObject?) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "export",
            ExistingWorkPolicy.APPEND,
            OneTimeWorkRequest.Builder(ApsResultExportWorker::class.java)
                .setInputData(dataWorkerStorage.storeInputData(ApsResultExportWorker.ApsResultData(algorithm, input, output)))
                .build()
        )
    }

    class ApsResultExportWorker(
        context: Context,
        params: WorkerParameters
    ) : LoggingWorker(context, params, Dispatchers.IO) {

        @Inject lateinit var prefFileList: PrefFileListProvider
        @Inject lateinit var storage: Storage
        @Inject lateinit var config: Config
        @Inject lateinit var dataWorkerStorage: DataWorkerStorage

        data class ApsResultData(val algorithm: String?, val input: JSONObject, val output: JSONObject?)

        override suspend fun doWorkAndLog(): Result {
            if (!config.isEngineeringMode()) return Result.success(workDataOf("Result" to "Export not enabled"))
            val apsResultData = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as ApsResultData?
                ?: return Result.failure(workDataOf("Error" to "missing input data"))

            prefFileList.ensureResultDirExists()
            val newFile = prefFileList.newResultFile()
            var ret = Result.success()
            try {
                val jsonObject = JSONObject().apply {
                    put("algorithm", apsResultData.algorithm)
                    put("input", apsResultData.input)
                    put("output", apsResultData.output)
                }
                storage.putFileContents(newFile, jsonObject.toString())
            } catch (e: FileNotFoundException) {
                aapsLogger.error(LTag.CORE, "Unhandled exception", e)
                ret = Result.failure(workDataOf("Error" to "Error FileNotFoundException"))
            } catch (e: IOException) {
                aapsLogger.error(LTag.CORE, "Unhandled exception", e)
                ret = Result.failure(workDataOf("Error" to "Error IOException"))
            }
            return ret
        }
    }
}