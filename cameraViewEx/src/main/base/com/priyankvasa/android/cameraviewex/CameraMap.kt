/*
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

package com.priyankvasa.android.cameraviewex

import android.hardware.camera2.CameraCharacteristics
import androidx.collection.ArrayMap

/**
 * A collection class that automatically groups [Size]s by their [AspectRatio]s.
 */
class CameraMap {

    private val cameras = ArrayMap<Int, ArrayList<Int>>().apply {
        this[Modes.Facing.FACING_BACK] = ArrayList()
        this[Modes.Facing.FACING_FRONT] = ArrayList()
    }
    private val characteristics = ArrayMap<Int, CameraCharacteristics>()

    fun add(facing: Int, cameraId: String, cameraCharacteristics: CameraCharacteristics?) {
        add(facing, Integer.parseInt(cameraId), cameraCharacteristics)
    }

    fun add(facing: Int, cameraId: Int, cameraCharacteristics: CameraCharacteristics?) {
        cameras[facing]?.add(cameraId)
        characteristics[cameraId] = cameraCharacteristics
    }

    fun camerasByFacing(facing: Int): ArrayList<Int> = cameras[facing] ?: ArrayList()

    fun characteristics(cameraId: Int): CameraCharacteristics? = characteristics[cameraId]

    /**
     * This will switch to the next camera, looping through all back and front
     * cameras
     */
    fun nextCamera(cameraId: Int): Int {
        val backCameras = camerasByFacing(Modes.Facing.FACING_BACK)
        val frontCameras = camerasByFacing(Modes.Facing.FACING_FRONT)
        when (facing(cameraId)) {
            Modes.Facing.FACING_BACK -> {
                val index = backCameras.indexOf(cameraId)
                return if (index + 1 == backCameras.size) {
                    frontCameras.first()
                } else {
                    backCameras[index + 1]
                }
            }
            Modes.Facing.FACING_FRONT -> {
                val index = frontCameras.indexOf(cameraId)
                return if (index + 1 == frontCameras.size) {
                    backCameras.first()
                } else {
                    frontCameras[index + 1]
                }
            }
        }
        return Modes.DEFAULT_FACING
    }

    fun facing(cameraId: Int): Int {
        if (camerasByFacing(Modes.Facing.FACING_FRONT).contains(cameraId)) {
            return Modes.Facing.FACING_FRONT
        }
        return Modes.Facing.FACING_BACK
    }
}