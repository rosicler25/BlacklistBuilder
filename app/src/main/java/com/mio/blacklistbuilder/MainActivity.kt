package com.mio.blacklistbuilder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    private lateinit var groupSpinner: Spinner
    private lateinit var infoText: TextView
    private lateinit var addPrefixCheck: CheckBox
    private lateinit var btnGenerateGroup: Button

    private var availableGroups = listOf<String>()
    private var selectedGroupName: String = "Lavoro"
    private var exportEmptyDb = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
        }

        val titleText = TextView(this).apply {
            text = "BlacklistBuilder v1.0"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val spinnerLabel = TextView(this).apply {
            text = "Scegli l'etichetta della rubrica:"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 15)
        }

        groupSpinner = Spinner(this).apply {
            setPadding(20, 20, 20, 20)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position in availableGroups.indices) {
                        selectedGroupName = availableGroups[position]
                        updateContactsPreview()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        infoText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30)
            setTextColor(Color.DKGRAY)
        }

        addPrefixCheck = CheckBox(this).apply {
            text = "  Aggiungi +39 ai numeri senza prefisso"
            textSize = 15f
            isChecked = true
            setPadding(10, 20, 10, 40)
            setOnCheckedChangeListener { _, _ -> updateContactsPreview() }
        }

        btnGenerateGroup = Button(this).apply {
            text = "ESPORTA BACKUP ETICHETTA (.gzz)"
            textSize = 16f
            setPadding(30, 40, 30, 40)
            setOnClickListener {
                exportEmptyDb = false
                val safeName = selectedGroupName.lowercase(Locale.ITALIAN).replace(" ", "_")
                askWhereToSaveFile("CallFilter_backup_$safeName.gzz")
            }
        }

        val spacer = TextView(this).apply {
            text = ""
            setPadding(0, 15, 0, 15)
        }

        val btnGenerateEmpty = Button(this).apply {
            text = "ESPORTA BACKUP VUOTO (.gzz)"
            textSize = 16f
            setPadding(30, 40, 30, 40)
            setOnClickListener {
                exportEmptyDb = true
                askWhereToSaveFile("CallFilter_backup_vuoto.gzz")
            }
        }

        layout.addView(titleText)
        layout.addView(spinnerLabel)
        layout.addView(groupSpinner)
        layout.addView(infoText)
        layout.addView(addPrefixCheck)
        layout.addView(btnGenerateGroup)
        layout.addView(spacer)
        layout.addView(btnGenerateEmpty)

        scrollView.addView(layout)
        setContentView(scrollView)

        checkContactsPermission()
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            loadAvailableGroups()
        }
    }

    private fun checkContactsPermission() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 100)
        } else {
            loadAvailableGroups()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadAvailableGroups()
        } else {
            infoText.text = "⚠️ Concedi il permesso Contatti per leggere le etichette."
            infoText.setTextColor(Color.RED)
        }
    }

    private fun loadAvailableGroups() {
        val groupsSet = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        val hiddenSystemTitles = setOf(
            "My Contacts", "Starred in Android", "ICE",
            "Coworkers", "Family", "Friends"
        )

        contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(
                ContactsContract.Groups.TITLE,
                ContactsContract.Groups.DELETED,
                ContactsContract.Groups.SYSTEM_ID,
                ContactsContract.Groups.FAVORITES,
                ContactsContract.Groups.AUTO_ADD
            ),
            null, null, null
        )?.use { cursor ->
            val titleIdx = cursor.getColumnIndex(ContactsContract.Groups.TITLE)
            val deletedIdx = cursor.getColumnIndex(ContactsContract.Groups.DELETED)
            val sysIdIdx = cursor.getColumnIndex(ContactsContract.Groups.SYSTEM_ID)
            val favIdx = cursor.getColumnIndex(ContactsContract.Groups.FAVORITES)
            val autoAddIdx = cursor.getColumnIndex(ContactsContract.Groups.AUTO_ADD)

            while (cursor.moveToNext()) {
                val isDeleted = if (deletedIdx != -1) cursor.getInt(deletedIdx) == 1 else false
                val systemId = if (sysIdIdx != -1) cursor.getString(sysIdIdx) else null
                val isFav = if (favIdx != -1) cursor.getInt(favIdx) == 1 else false
                val isAutoAdd = if (autoAddIdx != -1) cursor.getInt(autoAddIdx) == 1 else false
                val title = if (titleIdx != -1) cursor.getString(titleIdx)?.trim() else null

                // Filtra tutte le etichette di sistema nascoste, mostra solo quelle create dall'utente
                if (!isDeleted && !isFav && !isAutoAdd && systemId.isNullOrEmpty() && !title.isNullOrEmpty()) {
                    if (hiddenSystemTitles.none { it.equals(title, ignoreCase = true) }) {
                        groupsSet.add(title)
                    }
                }
            }
        }

        availableGroups = groupsSet.toList()

        if (availableGroups.isEmpty()) {
            infoText.text = "⚠️ Nessuna etichetta personalizzata trovata nella rubrica!"
            infoText.setTextColor(Color.RED)
            btnGenerateGroup.isEnabled = false
            return
        }

        btnGenerateGroup.isEnabled = true
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, availableGroups)
        groupSpinner.adapter = adapter

        val targetIndex = availableGroups.indexOfFirst { it.equals(selectedGroupName, ignoreCase = true) }
            .takeIf { it >= 0 }
            ?: availableGroups.indexOfFirst { it.equals("Lavoro", ignoreCase = true) }
            .takeIf { it >= 0 } ?: 0

        groupSpinner.setSelection(targetIndex)
        selectedGroupName = availableGroups[targetIndex]
        updateContactsPreview()
    }

    private fun updateContactsPreview() {
        if (availableGroups.isEmpty()) return
        val numbers = getPhoneNumbersForGroup(selectedGroupName)
        infoText.text = "✅ Etichetta '$selectedGroupName' selezionata\n" +
                "Pronti ${numbers.size} numeri di telefono\n" +
                "(Campo note: \"$selectedGroupName\")"
        infoText.setTextColor(Color.parseColor("#1565C0"))
        btnGenerateGroup.text = "ESPORTA '$selectedGroupName' (.gzz)"
    }

    private fun getPhoneNumbersForGroup(groupName: String): List<Pair<String, String>> {
        val groupIds = mutableSetOf<String>()
        contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.Groups._ID)
            val titleIdx = cursor.getColumnIndex(ContactsContract.Groups.TITLE)
            while (cursor.moveToNext()) {
                val id = if (idIdx != -1) cursor.getString(idIdx) else null
                val title = if (titleIdx != -1) cursor.getString(titleIdx)?.trim() else null
                if (id != null && title != null && title.equals(groupName, ignoreCase = true)) {
                    groupIds.add(id)
                }
            }
        }

        if (groupIds.isEmpty()) return emptyList()

        val contactIds = mutableSetOf<String>()
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
            null
        )?.use { cursor ->
            val cIdx = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            val gIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID)
            while (cursor.moveToNext()) {
                val cId = if (cIdx != -1) cursor.getString(cIdx) else null
                val gId = if (gIdx != -1) cursor.getString(gIdx) else null
                if (cId != null && gId != null && groupIds.contains(gId)) {
                    contactIds.add(cId)
                }
            }
        }

        val phoneMap = linkedMapOf<String, String>()
        if (contactIds.isNotEmpty()) {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                val cIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val cId = if (cIdx != -1) cursor.getString(cIdx) else null
                    if (cId != null && contactIds.contains(cId)) {
                        val rawNum = if (numIdx != -1) cursor.getString(numIdx) else null
                        val name = if (nameIdx != -1) cursor.getString(nameIdx) ?: "" else ""
                        if (!rawNum.isNullOrBlank()) {
                            val cleanNum = normalizePhone(rawNum, addPrefixCheck.isChecked)
                            if (cleanNum.isNotEmpty()) {
                                phoneMap[cleanNum] = name
                            }
                        }
                    }
                }
            }
        }

        return phoneMap.map { Pair(it.key, it.value) }
    }

    private fun normalizePhone(raw: String, addPlus39: Boolean): String {
        var cleaned = raw.replace(Regex("[^0-9+]"), "")
        if (cleaned.startsWith("00")) {
            cleaned = "+" + cleaned.substring(2)
        }
        if (addPlus39 && !cleaned.startsWith("+") && cleaned.isNotEmpty()) {
            cleaned = "+39$cleaned"
        }
        return cleaned
    }

    private fun askWhereToSaveFile(defaultFileName: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, defaultFileName)
        }
        startActivityForResult(intent, 300)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 300 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                buildAndExportGzz(uri, exportEmptyDb)
            }
        }
    }

    private fun buildAndExportGzz(destinationUri: Uri, isEmptyDb: Boolean) {
        val tempDbFile = File(cacheDir, "bwDB")
        if (tempDbFile.exists()) tempDbFile.delete()

        try {
            val db = SQLiteDatabase.openOrCreateDatabase(tempDbFile, null)
            db.disableWriteAheadLogging()
            db.rawQuery("PRAGMA journal_mode=DELETE", null).close()
            db.setLocale(Locale("it", "IT"))
            db.version = 3

            // Crea la tabella bwDB (e in automatico sqlite_sequence)
            db.execSQL("CREATE TABLE bwDB(id INTEGER PRIMARY KEY AUTOINCREMENT,phone TEXT,type INTEGER,name TEXT,comment TEXT)")

            var count = 0
            if (!isEmptyDb) {
                val numbers = getPhoneNumbersForGroup(selectedGroupName)
                val commentNote = selectedGroupName

                db.beginTransaction()
                try {
                    val stmt = db.compileStatement("INSERT INTO bwDB (phone, type, name, comment) VALUES (?, 1, ?, ?)")
                    for ((phone, name) in numbers) {
                        stmt.clearBindings()
                        stmt.bindString(1, phone)
                        stmt.bindString(2, name)
                        stmt.bindString(3, commentNote)
                        stmt.executeInsert()
                        count++
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            db.close()

            // Comprime il file chiamato "bwDB" in un archivio ZIP con estensione .gzz
            contentResolver.openOutputStream(destinationUri)?.use { outStream ->
                ZipOutputStream(outStream).use { zipOut ->
                    val zipEntry = ZipEntry("bwDB")
                    zipOut.putNextEntry(zipEntry)
                    FileInputStream(tempDbFile).use { fileIn ->
                        fileIn.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }

            val msg = if (isEmptyDb) {
                "Backup VUOTO salvato con successo!"
            } else {
                "Salvati $count numeri dell'etichetta '$selectedGroupName'!"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            if (tempDbFile.exists()) tempDbFile.delete()
        }
    }
}
