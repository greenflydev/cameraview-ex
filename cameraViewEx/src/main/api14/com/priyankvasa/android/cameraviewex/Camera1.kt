/*
 * Copyright 2018 Priyank Vasa
 *
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("DEPRECATION")

package com.priyankvasa.android.cameraviewex

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Build
import android.view.SurfaceHolder
import androidx.collection.SparseArrayCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.SortedSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList

internal class Camera1(
        override val listener: CameraInterface.Listener,
        override val preview: PreviewImpl,
        override val config: CameraConfiguration
) : CameraInterface {

    private val lifecycleRegistry: LifecycleRegistry =
            LifecycleRegistry(this).also { it.markState(Lifecycle.State.CREATED) }

    override fun getLifecycle(): Lifecycle = lifecycleRegistry

    private var cameraId: Int = Modes.Facing.FACING_BACK

    private val isPictureCaptureInProgress = AtomicBoolean(false)

    var camera: Camera? = null

    private var cameraParameters: Camera.Parameters? = null

    private val cameraInfo = Camera.CameraInfo()

    private val previewSizes = SizeMap()

    private val pictureSizes = SizeMap()

    private var aspectRatio: AspectRatio = Modes.DEFAULT_ASPECT_RATIO
        set(value) {
            if (field == value) return
            field = value
            if (isCameraOpened) {
                stop()
                start()
            }
        }

    private var showingPreview: Boolean = false

    override var jpegQuality: Int = Modes.DEFAULT_JPEG_QUALITY

    private var facing: Int = Modes.DEFAULT_FACING
        set(value) {
            if (field == value) return
            field = value
            if (isCameraOpened) {
                stop()
                start()
            }
        }

    override var displayOrientation: Int = 0
        set(value) {
            if (field == value) return
            field = value
            if (isCameraOpened) {
                try {
                    cameraParameters?.setRotation(calcCameraRotation(value))
                    camera?.parameters = cameraParameters
                    val needsToStopPreview = showingPreview && Build.VERSION.SDK_INT < 14
                    if (needsToStopPreview) camera?.stopPreview()
                    camera?.setDisplayOrientation(calcDisplayOrientation(value))
                    if (needsToStopPreview) camera?.startPreview()
                } catch (e: Exception) {
                    listener.onCameraError(e)
                }
            }
        }

    override var cameraOrientation: Int = 0

    override val isCameraOpened: Boolean get() = camera != null

    override var isVideoRecording: Boolean = false

    override val supportedAspectRatios: Set<AspectRatio>
        get() {
            previewSizes.ratios()
                    .asSequence()
                    .filter { pictureSizes.sizes(it).isEmpty() }
                    .forEach { previewSizes.remove(it) }
            return previewSizes.ratios()
        }

    private var autoFocus: Boolean = false
        get() {
            if (!isCameraOpened) return field
            val focusMode = cameraParameters?.focusMode
            return focusMode != null && focusMode.contains("continuous")
        }
        set(value) {
            if (field == value) return
            if (setAutoFocusInternal(value)) {
                field = value
                try {
                    camera?.parameters = cameraParameters
                } catch (e: RuntimeException) {
                    listener.onCameraError(e)
                }
            }
        }

    override val maxDigitalZoom: Float = 1f

    private var flash: Int = Modes.DEFAULT_FLASH
        set(value) {
            if (field == value) return
            if (isCameraOpened) {
                try {
                    val modes = cameraParameters?.supportedFlashModes
                    val mode = FLASH_MODES.get(value)
                    if (modes?.contains(mode) == true) {
                        cameraParameters?.flashMode = mode
                        field = value
                        camera?.parameters = cameraParameters
                    }
                    val currentMode = FLASH_MODES.get(field)
                    if (modes == null || !modes.contains(currentMode)) {
                        cameraParameters?.flashMode = Camera.Parameters.FLASH_MODE_OFF
                        field = Modes.Flash.FLASH_OFF
                        camera?.parameters = cameraParameters
                    }
                } catch (e: RuntimeException) {
                    listener.onCameraError(e)
                }
            } else field = value
        }

    private val previewSurfaceChangedListener: () -> Unit = {
        setUpPreview()
        adjustCameraParameters()
    }

    init {
        preview.surfaceChangeListener = previewSurfaceChangedListener
        addObservers()
    }

    private fun addObservers() {
        config.run {
            facing.observe(this@Camera1) { this@Camera1.facing = it }
            autoFocus.observe(this@Camera1) { this@Camera1.autoFocus = it != Modes.AutoFocus.AF_OFF }
            flash.observe(this@Camera1) { this@Camera1.flash = it }
            aspectRatio.observe(this@Camera1) { this@Camera1.aspectRatio = it }
        }
    }

    override fun start(): Boolean {
        chooseCamera()
        openCamera()
        if (preview.isReady) setUpPreview()
        showingPreview = true
        return try {
            camera?.startPreview()
            true
        } catch (e: RuntimeException) {
            listener.onCameraError(e)
            false
        }
    }

    /**
     * Can be used to open the camera to a specified cameraId
     */
    override fun start(cameraId: Int): Boolean {
        chooseCameraById(cameraId)
        openCamera()
        if (preview.isReady) setUpPreview()
        showingPreview = true
        return try {
            camera?.startPreview()
            true
        } catch (e: RuntimeException) {
            listener.onCameraError(e)
            false
        }
    }

    override fun stop() {
        runCatching { camera?.stopPreview() }.onFailure { listener.onCameraError(it as Exception) }
        showingPreview = false
        releaseCamera()
    }

    override fun facingByCameraId(cameraId: Int): Int {
        val info = android.hardware.Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return Modes.Facing.FACING_FRONT
        }
        return Modes.Facing.FACING_BACK
    }

    // Suppresses Camera#setPreviewTexture
    @SuppressLint("NewApi")
    fun setUpPreview() {
        try {
            if (preview.outputClass === SurfaceHolder::class.java) {
                val needsToStopPreview = showingPreview && Build.VERSION.SDK_INT < 14
                if (needsToStopPreview) {
                    camera?.stopPreview()
                }
                camera?.setPreviewDisplay(preview.surfaceHolder)
                if (needsToStopPreview) {
                    camera?.startPreview()
                }
            } else {
                camera?.setPreviewTexture(preview.surfaceTexture as SurfaceTexture)
            }
            lifecycleRegistry.markState(Lifecycle.State.STARTED)
        } catch (e: Exception) {
            listener.onCameraError(e)
        }
    }

    override fun setAspectRatio(ratio: AspectRatio): Boolean {
        if (!isCameraOpened) {
            // Handle this later when camera is opened
            aspectRatio = ratio
            return true
        } else if (aspectRatio != ratio) {
            val sizes = previewSizes.sizes(ratio)
            if (sizes.isEmpty()) {
                listener.onCameraError(UnsupportedOperationException("$ratio is not supported"))
            } else {
                aspectRatio = ratio
                adjustCameraParameters()
                return true
            }
        }
        return false
    }

    override fun takePicture() {
        if (!isCameraOpened) {
            listener.onCameraError(IllegalStateException("Camera is not ready. Call start() before capture()."))
        }
        try {
            if (this.autoFocus) {
                camera?.cancelAutoFocus()
                camera?.autoFocus { _, _ -> takePictureInternal() }
            } else {
                takePictureInternal()
            }
        } catch (e: RuntimeException) {
            listener.onCameraError(e)
        }
    }

    @Throws(RuntimeException::class)
    private fun takePictureInternal() {
        if (!isPictureCaptureInProgress.getAndSet(true)) {
            camera?.takePicture(null, null, null, Camera.PictureCallback { data, camera ->
                isPictureCaptureInProgress.set(false)
                listener.onPictureTaken(data)
                camera?.cancelAutoFocus()
                camera?.startPreview()
            })
        }
    }

    override fun startVideoRecording(outputFile: File, config: VideoConfiguration) {
    }

    override fun pauseVideoRecording(): Boolean = false

    override fun resumeVideoRecording(): Boolean = false

    override fun stopVideoRecording(): Boolean = false

    /**
     * This rewrites [.cameraId] and [.cameraInfo].
     */
    private fun chooseCamera() {
        /*
         * If the facing value is greater than 1 then treat this case special
         * and set the cameraId to what the facing value is.
         */
        if (config.facing.value > Modes.Facing.FACING_FRONT &&
                config.facing.value < Camera.getNumberOfCameras()) {
            cameraId = config.facing.value
            return
        }

        for (i in 0..(Camera.getNumberOfCameras()-1)) {
            Camera.getCameraInfo(i, cameraInfo)
            if (cameraInfo.facing == facing) {
                cameraId = i
                return
            }
        }
        cameraId = INVALID_CAMERA_ID
    }

    /**
     * Gets the cameraIds that are facing front or back.
     * Pass in either Modes.Facing.FACING_BACK or FACING_FRONT
     */
    override fun cameraIdsByFacing(facing: Int): List<Int> {
        val ids = mutableListOf<Int>()
        val info = android.hardware.Camera.CameraInfo()
        for (i in 0..(Camera.getNumberOfCameras()-1)) {
            Camera.getCameraInfo(i, info)
            if (facing == Modes.Facing.FACING_BACK &&
                    info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                ids.add(i)
            }
            else if (facing == Modes.Facing.FACING_FRONT &&
                    info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                ids.add(i)
            }
        }
        return ids
    }

    /**
     * Gets a list of focal lengths for the passed in cameraId
     * TODO: Not supporting for Camera1
     */
    override fun focalLengths(cameraId: Int): List<Float> {
        return ArrayList<Float>()
    }

    /**
     * This will choose a camera based on a passed in cameraId
     * Called from [start(cameraId)]
     */
    private fun chooseCameraById(cameraId: Int): Boolean {
        if (cameraId >= 0 && cameraId < Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(cameraId, cameraInfo)
            this.cameraId = cameraId
            return true
        }
        return false
    }

    private fun openCamera() {
        try {
            releaseCamera()
            camera = Camera.open(cameraId)
            cameraParameters = camera?.parameters
            // Supported preview sizes
            previewSizes.clear()
            cameraParameters?.supportedPreviewSizes?.forEach { size ->
                previewSizes.add(Size(size.width, size.height))
            }
            // Supported picture sizes;
            pictureSizes.clear()
            cameraParameters?.supportedPictureSizes?.forEach { size ->
                pictureSizes.add(Size(size.width, size.height))
            }
            adjustCameraParameters()
            camera?.setDisplayOrientation(calcDisplayOrientation(displayOrientation))
            GlobalScope.launch(Dispatchers.Main) { listener.onCameraOpened() }
        } catch (e: RuntimeException) {
            listener.onCameraError(e)
        }
    }

    private fun chooseAspectRatio(): AspectRatio {
        var r: AspectRatio = Modes.DEFAULT_ASPECT_RATIO
        for (ratio in previewSizes.ratios()) {
            r = ratio
            if (ratio == Modes.DEFAULT_ASPECT_RATIO) {
                return ratio
            }
        }
        return r
    }

    private fun adjustCameraParameters() {
        var sizes = previewSizes.sizes(aspectRatio)
        if (sizes.isEmpty()) { // Not supported
            aspectRatio = chooseAspectRatio()
            sizes = previewSizes.sizes(aspectRatio)
        }
        val size = chooseOptimalSize(sizes)

        // Always re-apply camera parameters
        // Largest picture size in this ratio
        val pictureSize = pictureSizes.sizes(aspectRatio).last()
        if (showingPreview) camera?.stopPreview()
        cameraParameters?.apply {
            setPreviewSize(size.width, size.height)
            setPictureSize(pictureSize.width, pictureSize.height)
            setRotation(calcCameraRotation(displayOrientation))
        }?.also { camera?.parameters = it }
        setAutoFocusInternal(autoFocus)
        setFlashInternal(flash)
        try {
            if (showingPreview) camera?.startPreview()

        } catch (e: RuntimeException) {
            listener.onCameraError(e)
        }
    }

    private fun chooseOptimalSize(sizes: SortedSet<Size>): Size {
        if (!preview.isReady) { // Not yet laid out
            return sizes.first() // Return the smallest size
        }
        val desiredWidth: Int
        val desiredHeight: Int
        val surfaceWidth = preview.width
        val surfaceHeight = preview.height
        if (isLandscape(displayOrientation)) {
            desiredWidth = surfaceHeight
            desiredHeight = surfaceWidth
        } else {
            desiredWidth = surfaceWidth
            desiredHeight = surfaceHeight
        }
        return sizes
                .firstOrNull { desiredWidth <= it.width && desiredHeight <= it.height }
                ?: sizes.last()
    }

    private fun releaseCamera() {
        camera?.release()
        camera = null
        GlobalScope.launch(Dispatchers.Main) { listener.onCameraClosed() }
    }

    /**
     * Calculate display orientation
     * https://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
     *
     *
     * This calculation is used for orienting the preview
     *
     *
     * Note: This is not the same calculation as the camera rotation
     *
     * @param screenOrientationDegrees Screen orientation in degrees
     * @return Number of degrees required to rotate preview
     */
    private fun calcDisplayOrientation(screenOrientationDegrees: Int): Int {
        return if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (360 - (cameraInfo.orientation + screenOrientationDegrees) % 360) % 360
        } else {  // back-facing
            (cameraInfo.orientation - screenOrientationDegrees + 360) % 360
        }
    }

    /**
     * Calculate camera rotation
     *
     *
     * This calculation is applied to the output JPEG either via Exif Orientation tag
     * or by actually transforming the bitmap. (Determined by vendor camera API implementation)
     *
     *
     * Note: This is not the same calculation as the display orientation
     *
     * @param screenOrientationDegrees Screen orientation in degrees
     * @return Number of degrees to rotate image in order for it to view correctly.
     */
    private fun calcCameraRotation(screenOrientationDegrees: Int): Int {
        return when (cameraInfo.facing) {
            Camera.CameraInfo.CAMERA_FACING_FRONT ->
                (cameraInfo.orientation + screenOrientationDegrees) % 360
            else -> {  // back-facing
                val landscapeFlip = if (isLandscape(screenOrientationDegrees)) 180 else 0
                (cameraInfo.orientation + screenOrientationDegrees + landscapeFlip) % 360
            }
        }
    }

    /**
     * Test if the supplied orientation is in landscape.
     *
     * @param orientationDegrees Orientation in degrees (0,90,180,270)
     * @return True if in landscape, false if portrait
     */
    private fun isLandscape(orientationDegrees: Int): Boolean {
        return orientationDegrees == Modes.LANDSCAPE_90 || orientationDegrees == Modes.LANDSCAPE_270
    }

    /**
     * @return `true` if [.cameraParameters] was modified.
     */
    private fun setAutoFocusInternal(autoFocus: Boolean): Boolean = isCameraOpened.also {

        if (!it) return@also

        val modes = cameraParameters?.supportedFocusModes

        cameraParameters?.focusMode = when {
            autoFocus && modes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true ->
                Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            modes?.contains(Camera.Parameters.FOCUS_MODE_FIXED) == true ->
                Camera.Parameters.FOCUS_MODE_FIXED
            modes?.contains(Camera.Parameters.FOCUS_MODE_INFINITY) == true ->
                Camera.Parameters.FOCUS_MODE_INFINITY
            else -> modes?.get(0)
        }
    }

    private fun setFlashInternal(flash: Int) {
        this.flash = flash
    }

    companion object {

        private const val INVALID_CAMERA_ID = -1

        private val FLASH_MODES = SparseArrayCompat<String>()

        init {
            FLASH_MODES.put(Modes.Flash.FLASH_OFF, Camera.Parameters.FLASH_MODE_OFF)
            FLASH_MODES.put(Modes.Flash.FLASH_ON, Camera.Parameters.FLASH_MODE_ON)
            FLASH_MODES.put(Modes.Flash.FLASH_TORCH, Camera.Parameters.FLASH_MODE_TORCH)
            FLASH_MODES.put(Modes.Flash.FLASH_AUTO, Camera.Parameters.FLASH_MODE_AUTO)
            FLASH_MODES.put(Modes.Flash.FLASH_RED_EYE, Camera.Parameters.FLASH_MODE_RED_EYE)
        }
    }
}