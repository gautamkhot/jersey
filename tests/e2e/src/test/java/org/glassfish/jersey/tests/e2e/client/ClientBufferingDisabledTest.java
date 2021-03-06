/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.tests.e2e.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.HttpUrlConnector;
import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;

import org.junit.Assert;
import org.junit.Test;

/**
 *
 * @author Miroslav Fuksa (miroslav.fuksa at oracle.com)
 */
public class ClientBufferingDisabledTest extends JerseyTest {

    private static final long LENGTH = 200000000l;
    private static final int CHUNK = 2048;
    private static CountDownLatch postLatch = new CountDownLatch(1);

    @Override
    protected Application configure() {
        return new ResourceConfig(MyResource.class);
    }

    @Path("resource")
    public static class MyResource {

        @POST
        public long post(InputStream is) throws IOException {
            int b;
            long count = 0;
            boolean firstByte = true;
            while ((b = is.read()) != -1) {
                if (firstByte) {
                    firstByte = false;
                    postLatch.countDown();
                }

                count++;
            }
            return count;
        }
    }


    /**
     * Test that buffering can be disabled with {@link HttpURLConnection}. By default, the
     * {@code HttpURLConnection} buffers the output entity in order to calculate the
     * Content-length request attribute. This cause problems for large entities.
     * <p>
     * This test currently uses {@link HttpUrlConnector.ConnectionFactory} to setup
     * chunk output parameter on the connection. The other way is to use
     * {@link HttpURLConnection#setFixedLengthStreamingMode(int)} method to disable
     * buffering. In the future it should use the support which allows to
     * disable buffering
     * <p/>
     * <p>
     * The similar functionality was in Jersey 1.x but did not work due to bug
     * in {@code HttpURLConnection} - this should be firstly investigated before
     * implementing the issue.
     * <p/>
     */
    @Test
    // TODO: this is workaround to https://java.net/jira/browse/JERSEY-2024
    // implement test without using ConnectionFactory after the issue is fixed.
    public void testDisableBuffering() {

        HttpUrlConnector.ConnectionFactory factory = new HttpUrlConnector.ConnectionFactory() {
            @Override
            public HttpURLConnection getConnection(URL endpointUrl) throws IOException {
                HttpURLConnection conn = (HttpURLConnection) endpointUrl.openConnection();
                conn.setChunkedStreamingMode(CHUNK);
                return conn;
            }
        };

        // This IS sends out 10 chunks and waits whether they were received on the server. This tests
        // whether the buffering is disabled.
        InputStream is = new InputStream() {
            private int cnt = 0;

            @Override
            public int read() throws IOException {
                cnt++;
                if (cnt > CHUNK * 10) {
                    try {
                        postLatch.await(3, TimeUnit.SECONDS);
                        Assert.assertEquals("waiting for chunk on the server side time-outed", 0, postLatch.getCount());
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (cnt <= LENGTH) {
                    return 'a';
                } else {
                    return -1;
                }
            }
        };

        ClientConfig clientConfig = new ClientConfig();
        Connector connector = new HttpUrlConnector(factory);
        clientConfig.connector(connector);
        Client client = ClientBuilder.newClient(clientConfig);
        final Response response
                = client.target(getBaseUri()).path("resource")
                .request().post(Entity.entity(is, MediaType.APPLICATION_OCTET_STREAM));
        Assert.assertEquals(200, response.getStatus());
        final long count = response.readEntity(long.class);
        System.out.println(count);
        Assert.assertEquals(LENGTH, count);
    }
}
