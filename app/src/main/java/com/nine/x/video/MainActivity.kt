package com.nine.x.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.widget.TextView
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.nine.x.video.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class MainActivity : AppCompatActivity() {

    private val TAG = MainActivity::class.java.simpleName

    private lateinit var binding: ActivityMainBinding


    val cameraViewModel: CameraViewModel = ViewModelProvider(this)[CameraViewModel::class.java]



//    private val characteristics: CameraCharacteristics by lazy {
////        cameraManager.getCameraCharacteristics(args.cameraId)
//    }

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private lateinit var camera: CameraDevice

    private lateinit var session: CameraCaptureSession

    private val cameraHandler = Handler(cameraThread.looper)

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    private val cameraManager: CameraManager by lazy {
        baseContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Example of a call to a native method
    }

//    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
//        // Open the selected camera
//        camera = openCamera(cameraManager, args.cameraId, cameraHandler)
//
//        // Initialize an image reader which will be used to capture still photos
//        val size = characteristics.get(
//            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
//            .getOutputSizes(args.pixelFormat).maxByOrNull { it.height * it.width }!!
//        imageReader = ImageReader.newInstance(
//            size.width, size.height, args.pixelFormat, IMAGE_BUFFER_SIZE)
//
//        // Creates list of Surfaces where the camera will output frames
//        val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)
//
//        // Start a capture session using our open camera and list of Surfaces where frames will go
//        session = createCaptureSession(camera, targets, cameraHandler)
//
//        val captureRequest = camera.createCaptureRequest(
//            CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(fragmentCameraBinding.viewFinder.holder.surface) }
//
//        // This will keep sending the capture request as frequently as possible until the
//        // session is torn down or session.stopRepeating() is called
//        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
//
//        // Listen to the capture button
////        fragmentCameraBinding.captureButton.setOnClickListener {
////
////            // Disable click listener to prevent multiple requests simultaneously in flight
////            it.isEnabled = false
////
////            // Perform I/O heavy operations in a different scope
////            lifecycleScope.launch(Dispatchers.IO) {
////                takePhoto().use { result ->
////                    Log.d(TAG, "Result received: $result")
////
////                    // Save the result to disk
////                    val output = saveResult(result)
////                    Log.d(TAG, "Image saved: ${output.absolutePath}")
////
////                    // If the result is a JPEG file, update EXIF metadata with orientation info
////                    if (output.extension == "jpg") {
////                        val exif = ExifInterface(output.absolutePath)
////                        exif.setAttribute(
////                            ExifInterface.TAG_ORIENTATION, result.orientation.toString())
////                        exif.saveAttributes()
////                        Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
////                    }
////
////                    // Display the photo taken to user
////                    lifecycleScope.launch(Dispatchers.Main) {
////                        navController.navigate(CameraFragmentDirections
////                            .actionCameraToJpegViewer(output.absolutePath)
////                            .setOrientation(result.orientation)
////                            .setDepth(
////                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
////                                    result.format == ImageFormat.DEPTH_JPEG))
////                    }
////                }
////
////                // Re-enable click listener after photo is taken
////                it.post { it.isEnabled = true }
////            }
////        }
//    }


    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }


    companion object {
        private val TAG = MainActivity::class.java.simpleName

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")
        }
    }
}