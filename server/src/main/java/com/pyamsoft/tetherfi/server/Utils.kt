/*
 * Copyright 2024 pyamsoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pyamsoft.tetherfi.server

import kotlin.time.Duration.Companion.seconds

/**
 * What the fuck is this
 * https://stackoverflow.com/questions/10006459/regular-expression-for-ip-address-validation
 *
 * Tests if a given string is an IP address
 */
@JvmField
val IP_ADDRESS_REGEX =
    """^(\d|[1-9]\d|1\d\d|2([0-4]\d|5[0-5]))\.(\d|[1-9]\d|1\d\d|2([0-4]\d|5[0-5]))\.(\d|[1-9]\d|1\d\d|2([0-4]\d|5[0-5]))\.(\d|[1-9]\d|1\d\d|2([0-4]\d|5[0-5]))$"""
        .toRegex()

/**
 * KTOR sockets by default do not time out
 *
 * We want a socket to die if it has been idle for X amount of time to avoid hogging memory which
 * leads to device slowdowns
 */
val SOCKET_TIMEOUT_DURATION = 30.seconds
