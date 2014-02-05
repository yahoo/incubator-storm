/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package backtype.storm.drpc;

import java.util.Map;

import backtype.storm.generated.DRPCRequest;
import backtype.storm.generated.DistributedRPCInvocations;
import backtype.storm.generated.AuthorizationException;
import backtype.storm.security.auth.ThriftClient;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.TException;

public class DRPCInvocationsClient extends ThriftClient implements DistributedRPCInvocations.Iface {
    private DistributedRPCInvocations.Client client;
    private String host;
    private int port;    

    public DRPCInvocationsClient(Map conf, String host, int port) throws TTransportException {
        super(conf, host, port, null);
        this.host = host;
        this.port = port;
        client = new DistributedRPCInvocations.Client(_protocol);
    }
        
    public String getHost() {
        return host;
    }
    
    public int getPort() {
        return port;
    }       

    public void result(String id, String result) throws TException, AuthorizationException {
        try {
            client.result(id, result);
        } catch(TException e) {
            client = null;
            throw e;
        }
    }

    public DRPCRequest fetchRequest(String func) throws TException, AuthorizationException {
        try {
            return client.fetchRequest(func);
        } catch(TException e) {
            client = null;
            throw e;
        }
    }    

    public void failRequest(String id) throws TException, AuthorizationException {
        try {
            client.failRequest(id);
        } catch(TException e) {
            client = null;
            throw e;
        }
    }

    public DistributedRPCInvocations.Client getClient() {
        return client;
    }
}
