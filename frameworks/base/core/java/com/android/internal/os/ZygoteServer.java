/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.os;

import static android.system.OsConstants.POLLIN;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.system.Os;
import android.system.ErrnoException;
import android.system.StructPollfd;
import android.util.Log;

import java.io.IOException;
import java.io.FileDescriptor;
import java.util.ArrayList;

/**
 * Server socket class for zygote processes.
 *
 * Provides functions to wait for commands on a UNIX domain socket, and fork
 * off child processes that inherit the initial state of the VM.%
 *
 * Please see {@link ZygoteConnection.Arguments} for documentation on the
 * client protocol.
 */
class ZygoteServer {
    public static final String TAG = "ZygoteServer";

    private static final String ANDROID_SOCKET_PREFIX = "ANDROID_SOCKET_";

    private LocalServerSocket mServerSocket;

    ZygoteServer() {
    }

    /**
     * Registers a server socket for zygote command connections
     *
     * @throws RuntimeException when open fails
     */
    // socketName = “zygote” 
    void registerServerSocket(String socketName) {
        if (mServerSocket == null) {
            int fileDesc;
			// 1. 拼接 Socket 的名称 ANDROID_SOCKET_zygote
            final String fullSocketName = ANDROID_SOCKET_PREFIX + socketName;
            try {
				// 2. 得到 Socket 的环境变量的值
                String env = System.getenv(fullSocketName);
				// 3. 将 Socket 环境变量的值转换为文件描述符的参数
                fileDesc = Integer.parseInt(env);
            } catch (RuntimeException ex) {
                throw new RuntimeException(fullSocketName + " unset or invalid", ex);
            }

            try {
				// 4. 创建文件描述符
                FileDescriptor fd = new FileDescriptor();
				// 5.
                fd.setInt$(fileDesc);
				// 6. 创建服务器端 Socket
                mServerSocket = new LocalServerSocket(fd);
            } catch (IOException ex) {
                throw new RuntimeException(
                        "Error binding to local socket '" + fileDesc + "'", ex);
            }
        }
    }

    /**
     * Waits for and accepts a single command connection. Throws
     * RuntimeException on failure.
     */
    private ZygoteConnection acceptCommandPeer(String abiList) {
        try {
            return createNewConnection(mServerSocket.accept(), abiList);
        } catch (IOException ex) {
            throw new RuntimeException(
                    "IOException during accept()", ex);
        }
    }

    protected ZygoteConnection createNewConnection(LocalSocket socket, String abiList)
            throws IOException {
        return new ZygoteConnection(socket, abiList);
    }

    /**
     * Close and clean up zygote sockets. Called on shutdown and on the
     * child's exit path.
     */
    void closeServerSocket() {
        try {
            if (mServerSocket != null) {
                FileDescriptor fd = mServerSocket.getFileDescriptor();
                mServerSocket.close();
                if (fd != null) {
                    Os.close(fd);
                }
            }
        } catch (IOException ex) {
            Log.e(TAG, "Zygote:  error closing sockets", ex);
        } catch (ErrnoException ex) {
            Log.e(TAG, "Zygote:  error closing descriptor", ex);
        }

        mServerSocket = null;
    }

    /**
     * Return the server socket's underlying file descriptor, so that
     * ZygoteConnection can pass it to the native code for proper
     * closure after a child process is forked off.
     */

    FileDescriptor getServerSocketFileDescriptor() {
        return mServerSocket.getFileDescriptor();
    }

    /**
     * Runs the zygote process's select loop. Accepts new connections as
     * they happen, and reads commands from connections one spawn-request's
     * worth at a time.
     *
     * @throws Zygote.MethodAndArgsCaller in a child process when a main()
     * should be executed.
     */
    void runSelectLoop(String abiList) throws Zygote.MethodAndArgsCaller {
        ArrayList<FileDescriptor> fds = new ArrayList<FileDescriptor>();
        ArrayList<ZygoteConnection> peers = new ArrayList<ZygoteConnection>();

		// 1. 获得该 Socket 的 fd 字段的值并添加到 fd 列表 fds 中
        fds.add(mServerSocket.getFileDescriptor());
        peers.add(null);

		// 无限循环等待 AMS 的请求
        while (true) {
            StructPollfd[] pollFds = new StructPollfd[fds.size()];
			// 2. 遍历将 fds 存储的信息转移到 pollFds 数组中
            for (int i = 0; i < pollFds.length; ++i) {
                pollFds[i] = new StructPollfd();
                pollFds[i].fd = fds.get(i);
                pollFds[i].events = (short) POLLIN;
            }
            try {
                Os.poll(pollFds, -1);
            } catch (ErrnoException ex) {
                throw new RuntimeException("poll failed", ex);
            }
			// 3. 对 pollFds 进行遍历，如果 i==O ，说明服务器端 Socket 与客户端连接上了
            for (int i = pollFds.length - 1; i >= 0; --i) {
                if ((pollFds[i].revents & POLLIN) == 0) {
                    continue;
                }

                if (i == 0) {
					// 4. 链接上：当前 Zygote 进程与 AMS 建立了链接。
					// 通过 acceptCommandPeer 方法得到 ZygoteConnection类并添加到 Socket 连接列表 peers 中
                    ZygoteConnection newPeer = acceptCommandPeer(abiList);
                    peers.add(newPeer);
                    fds.add(newPeer.getFileDesciptor());
                } else {
					// 5. AMS 向 Zygote 进程发送了一个创建应用进程的请求
					// runOnce 函数来创建一个新的应用程序进程
                    boolean done = peers.get(i).runOnce(this);
                    if (done) {
                        peers.remove(i);
                        fds.remove(i);
                    }
                }
            }
        }
    }
}
