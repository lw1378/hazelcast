/*
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.protocol;

import com.hazelcast.core.Hazelcast;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class ProtocolTest {

    Socket socket;

    @BeforeClass
    public static void init() throws Exception {
        Hazelcast.getCluster();
    }

    @After
    public void cleanup() throws Exception {
        Hazelcast.shutdownAll();
    }

    @Before
    public void connect() throws Exception {
        this.socket = connect0();
    }

    protected Socket connect0() throws IOException {
        final InetSocketAddress isa = new InetSocketAddress("localhost", 5701);
        Socket socket = new Socket();
        socket.setKeepAlive(true);
        socket.setSoLinger(true, 5);
        socket.connect(isa, 3000);
        OutputStream out = socket.getOutputStream();
        out.write("P01".getBytes());
        out.flush();
        auth(socket);
        return socket;
    }

    protected OutputStream doOp(String commandLine, String sizeLine, Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(out, "UTF-8");
        writer.write(commandLine);
        writer.write("\r\n");
        writer.write(sizeLine);
        writer.write("\r\n");
        writer.flush();
        return out;
    }

    private void auth(Socket socket) throws IOException {
        doOp("AUTH 0 dev dev-pass", "#0", socket);
        read(socket);
    }

    protected List<String> read(Socket socket) throws IOException {
        List<String> values = new ArrayList<String>();
        InputStreamReader reader = new InputStreamReader(socket.getInputStream(), "UTF-8");
        BufferedReader buf = new BufferedReader(reader);
        String commandLine = buf.readLine();
        String sizeLine = buf.readLine();
        System.out.println(commandLine);
        System.out.println(sizeLine);
        String[] tokens = sizeLine.split(" ");
        int count = Integer.valueOf(tokens[0].substring(1));
//        System.out.println("Count is " + count);
        for (int i = 0; i < count; i++) {
            int s = Integer.valueOf(tokens[i + 1]);
//            System.out.println("Size is " + s);
            char[] value = new char[s];
            buf.read(value);
            System.out.println(String.valueOf(value));
            values.add(String.valueOf(value));
        }
        if (count == 0) {
            values.add(commandLine.split(" ")[0]);
        }
        return values;
    }

    protected OutputStream doOp(String commandLine, String sizeLine) throws IOException {
        return doOp(commandLine, sizeLine, socket);
    }
}
