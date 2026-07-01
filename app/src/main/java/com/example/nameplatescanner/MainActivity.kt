package com.example.nameplatescanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var btnTakePhoto: Button
    private lateinit var btnAnalyze: Button
    private lateinit var btnExport: Button
    private lateinit var imageView: ImageView
    private lateinit var tvResult: TextView
    
    private var photoUri: Uri? = null
    private var currentPhotoPath: String? = null
    private var recognizedText: String = ""
    private var nameplateData: Map<String, String> = emptyMap()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(this, "Нужно разрешение камеры", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            imageView.setImageURI(photoUri)
            Toast.makeText(this, "Фото сделано", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        btnExport = findViewById(R.id.btnExport)
        imageView = findViewById(R.id.imageView)
        tvResult = findViewById(R.id.tvResult)

        btnTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                launchCamera()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        btnAnalyze.setOnClickListener {
            currentPhotoPath?.let { path ->
                analyzeNameplate(path)
            } ?: Toast.makeText(this, "Сначала сделайте фото", Toast.LENGTH_SHORT).show()
        }

        btnExport.setOnClickListener {
            if (nameplateData.isNotEmpty()) {
                exportToExcel()
            } else {
                Toast.makeText(this, "Сначала проанализируйте шильдик", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchCamera() {
        val photoFile = try {
            createImageFile()
        } catch (ex: Exception) {
            Toast.makeText(this, "Ошибка создания файла", Toast.LENGTH_SHORT).show()
            return
        }

        photoUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        takePictureLauncher.launch(photoUri)
    }

    @Throws(Exception::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("nameplate_${timeStamp}_", ".jpg", storageDir).also {
            currentPhotoPath = it.absolutePath
        }
    }

    private fun analyzeNameplate(imagePath: String) {
        val image = InputImage.fromFilePath(this, Uri.fromFile(File(imagePath)))
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                recognizedText = visionText.text
                nameplateData = parseNameplateData(recognizedText)
                tvResult.text = buildResultText()
                Toast.makeText(this, "Анализ завершён", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun parseNameplateData(text: String): Map<String, String> {
        val data = mutableMapOf<String, String>()
        
        // Простой парсинг - ищем ключевые слова
        val lines = text.split("\n")
        for (line in lines) {
            when {
                line.contains("kW", ignoreCase = true) -> data["power_kw"] = extractNumber(line)
                line.contains("HP", ignoreCase = true) -> data["power_hp"] = extractNumber(line)
                line.contains("V", ignoreCase = true) && line.contains("Hz", ignoreCase = true) -> {
                    data["voltage"] = extractVoltage(line)
                }
                line.contains("RPM", ignoreCase = true) || line.contains("min", ignoreCase = true) -> {
                    data["rpm"] = extractNumber(line)
                }
                line.contains("IP", ignoreCase = true) -> data["protection_class"] = extractIP(line)
                line.contains("S/N", ignoreCase = true) || line.contains("Serial", ignoreCase = true) -> {
                    data["serial_number"] = extractAfterColon(line)
                }
                line.contains("Model", ignoreCase = true) -> data["model"] = extractAfterColon(line)
            }
        }
        
        data["raw_text"] = text
        return data
    }

    private fun extractNumber(text: String): String {
        val regex = Regex("\\d+([.,]\\d+)?")
        return regex.find(text)?.value ?: ""
    }

    private fun extractVoltage(text: String): String {
        val regex = Regex("\\d+\\s*V")
        return regex.find(text)?.value ?: ""
    }

    private fun extractIP(text: String): String {
        val regex = Regex("IP\\d+")
        return regex.find(text)?.value ?: ""
    }

    private fun extractAfterColon(text: String): String {
        return text.split(":").getOrNull(1)?.trim() ?: ""
    }

    private fun buildResultText(): String {
        val sb = StringBuilder()
        sb.appendLine("Распознанные данные:")
        sb.appendLine("Мощность: ${nameplateData["power_kw"] ?: "—"} кВт")
        sb.appendLine("Напряжение: ${nameplateData["voltage"] ?: "—"}")
        sb.appendLine("Обороты: ${nameplateData["rpm"] ?: "—"} об/мин")
        sb.appendLine("Класс защиты: ${nameplateData["protection_class"] ?: "—"}")
        sb.appendLine("Модель: ${nameplateData["model"] ?: "—"}")
        sb.appendLine("Серийный номер: ${nameplateData["serial_number"] ?: "—"}")
        return sb.toString()
    }

    private fun exportToExcel() {
        try {
            val workbook: Workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("Шильдик")

            val headerStyle = workbook.createCellStyle().apply {
                val font = workbook.createFont().apply {
                    bold = true
                    fontHeightInPoints = 12
                }
                setFont(font)
            }

            val rowHeader = sheet.createRow(0)
            rowHeader.createCell(0).setCellValue("Параметр").cellStyle = headerStyle
            rowHeader.createCell(1).setCellValue("Значение").cellStyle = headerStyle

            var rowNum = 1
            nameplateData.forEach { (key, value) ->
                if (key != "raw_text") {
                    val row = sheet.createRow(rowNum++)
                    row.createCell(0).setCellValue(key)
                    row.createCell(1).setCellValue(value)
                }
            }

            sheet.autoSizeColumn(0)
            sheet.autoSizeColumn(1)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "nameplate_$timeStamp.xlsx"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

            FileOutputStream(file).use { fos ->
                workbook.write(fos)
            }
            workbook.close()

            Toast.makeText(this, "Excel сохранён: $fileName", Toast.LENGTH_LONG).show()

            // Поделиться файлом
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Поделиться Excel"))

        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
