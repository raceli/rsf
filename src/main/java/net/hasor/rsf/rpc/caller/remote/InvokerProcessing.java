/*
 * Copyright 2008-2009 the original author or authors.
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
package net.hasor.rsf.rpc.caller.remote;
import net.hasor.core.Provider;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfEnvironment;
import net.hasor.rsf.RsfFilter;
import net.hasor.rsf.SerializeCoder;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfRuntimeUtils;
import net.hasor.rsf.domain.RsfServiceType;
import net.hasor.rsf.rpc.caller.RsfFilterHandler;
import net.hasor.rsf.rpc.caller.RsfResponseObject;
import net.hasor.rsf.transform.codec.CodecAdapter;
import net.hasor.rsf.transform.codec.CodecAdapterFactory;
import net.hasor.rsf.transform.protocol.RequestInfo;
import net.hasor.rsf.transform.protocol.ResponseInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;
/**
 * 负责处理远程Request对象的请求调用，同时也负责将产生的Response对象写回客户端。
 * @version : 2014年11月4日
 * @author 赵永春(zyc@hasor.net)
 */
abstract class InvokerProcessing implements Runnable {
    protected Logger logger = LoggerFactory.getLogger(getClass());
    private final RemoteRsfCaller rsfCaller;
    private final InterAddress    target;
    private final RequestInfo     requestInfo;
    private final ClassLoader     classLoader;
    private final RsfEnvironment  rsfEnv;
    private final CodecAdapter    codecAdapter;
    //
    public InvokerProcessing(InterAddress target, RemoteRsfCaller rsfCaller, RequestInfo requestInfo) {
        this.target = target;
        this.rsfCaller = rsfCaller;
        this.requestInfo = requestInfo;
        this.classLoader = rsfCaller.getContext().getClassLoader();
        this.rsfEnv = rsfCaller.getContainer().getEnvironment();
        this.codecAdapter = CodecAdapterFactory.getCodecAdapterByVersion(this.rsfEnv, this.requestInfo.getVersion());
    }
    public InterAddress getTarget() {
        return target;
    }
    public RemoteRsfCaller getRsfCaller() {
        return this.rsfCaller;
    }
    //
    public void run() {
        //
        /*正确性检验。*/
        long requestID = this.requestInfo.getRequestID();
        String group = this.requestInfo.getServiceGroup();
        String name = this.requestInfo.getServiceName();
        String version = this.requestInfo.getServiceVersion();
        RsfBindInfo<?> bindInfo = this.rsfCaller.getContainer().getRsfBindInfo(group, name, version);
        if (bindInfo == null || RsfServiceType.Provider != bindInfo.getServiceType()) {
            String serviceID = "[" + group + "]" + name + "-" + version;
            String errorInfo = "do request(" + requestID + ") failed -> service " + serviceID + " not exist.";
            logger.error(errorInfo);
            ResponseInfo info = this.codecAdapter.buildResponseStatus(requestID, ProtocolStatus.NotFound, errorInfo);
            this.sendResponse(info);
            return;
        }
        /*检查timeout。*/
        long lostTime = System.currentTimeMillis() - this.requestInfo.getReceiveTime();
        int clientTimeout = this.requestInfo.getClientTimeout();
        int timeout = this.validateTimeout(clientTimeout, bindInfo);
        if (lostTime > timeout) {
            String errorInfo = "do request(" + requestID + ") failed -> timeout for server.";
            logger.error(errorInfo);
            ResponseInfo info = this.codecAdapter.buildResponseStatus(requestID, ProtocolStatus.Timeout, errorInfo);
            this.sendResponse(info);
            return;
        }
        /*准备参数*/
        String serializeType = this.requestInfo.getSerializeType();
        Object[] pObjects = null;
        Class<?>[] pTypes = null;
        try {
            //1.确定序列化器
            SerializeCoder coder = this.rsfEnv.getSerializeCoder(serializeType);
            if (coder == null) {
                String errorInfo = "do request(" + requestID + ") failed -> serializeType(" + serializeType + ") is undefined.";
                logger.error(errorInfo);
                ResponseInfo info = this.codecAdapter.buildResponseStatus(requestID, ProtocolStatus.SerializeForbidden, errorInfo);
                this.sendResponse(info);
                return;
            }
            //2.参数数量校验
            List<String> pTypeList = this.requestInfo.getParameterTypes();
            List<byte[]> pObjectList = this.requestInfo.getParameterValues();
            if (pTypeList.size() != pObjectList.size()) {
                String errorInfo = "do request(" + requestID + ") failed -> parameters count and types count, not equal.";
                logger.error(errorInfo);
                ResponseInfo info = this.codecAdapter.buildResponseStatus(requestID, ProtocolStatus.InvokeError, errorInfo);
                this.sendResponse(info);
                return;
            }
            //3.反序列化
            pTypes = new Class<?>[pTypeList.size()];
            pObjects = new Object[pObjectList.size()];
            for (int i = 0; i < pTypeList.size(); i++) {
                String paramTypeStr = pTypeList.get(i);
                byte[] paramObjectStr = pObjectList.get(i);
                //
                pTypes[i] = RsfRuntimeUtils.getType(paramTypeStr, this.classLoader);
                pObjects[i] = coder.decode(paramObjectStr, pTypes[i]);
            }
        } catch (Throwable e) {
            String errorInfo = "do request(" + requestID + ") failed -> serializeType(" + serializeType + ") ,serialize error: " + e.getMessage();
            logger.error(errorInfo, e);
            ResponseInfo info = this.codecAdapter.buildResponseStatus(requestID, ProtocolStatus.SerializeError, errorInfo);
            this.sendResponse(info);
            return;
        }
        /*执行调用*/
        Method targetMethod = null;
        try {
            String methodName = this.requestInfo.getTargetMethod();
            targetMethod = bindInfo.getBindType().getMethod(methodName, pTypes);
        } catch (Throwable e) {
            String errorInfo = "do request(" + requestID + ") failed -> lookup service method error " + e.getMessage();
            logger.error(errorInfo, e);
            ResponseInfo info = this.codecAdapter.buildResponseStatus(requestID, ProtocolStatus.Forbidden, errorInfo);
            this.sendResponse(info);
            return;
        }
        //
        try {
            RsfRequestFormRemote rsfRequest = new RsfRequestFormRemote(this.target, this.requestInfo, bindInfo, targetMethod, pObjects, this.rsfCaller);
            RsfResponseObject rsfResponse = new RsfResponseObject(rsfRequest);
            rsfResponse.addOptionMap(this.rsfCaller.getContext().getSettings().getServerOption());//填充服务端的选项参数，并将选项参数响应到客户端。
            //
            String serviceID = bindInfo.getBindID();
            Provider<RsfFilter>[] rsfFilters = this.rsfCaller.getContainer().getFilterProviders(serviceID);
            new RsfFilterHandler(rsfFilters, RsfInvokeFilterChain.Default).doFilter(rsfRequest, rsfResponse);
            //
            this.sendResponse(rsfResponse);//将Response写入客户端。
        } catch (Throwable e) {
            String msgLog = "do request(" + requestID + ") failed -> service " + bindInfo.getBindID() + "," + e.getMessage();
            logger.error(msgLog);
            ResponseInfo info = this.codecAdapter.buildResponseStatus(requestID, ProtocolStatus.InvokeError, msgLog);
            this.sendResponse(info);
        }
    }
    private int validateTimeout(int timeout, RsfBindInfo<?> bindInfo) {
        if (timeout <= 0) {
            timeout = this.rsfCaller.getContext().getSettings().getDefaultTimeout();
        }
        if (timeout > bindInfo.getClientTimeout()) {
            timeout = bindInfo.getClientTimeout();
        }
        return timeout;
    }
    private void sendResponse(RsfResponseObject rsfResponse) {
        if (this.requestInfo.isMessage()) {
            return;/*如果是消息类型调用,则丢弃response*/
        }
        //
        String serializeType = this.requestInfo.getSerializeType();
        long requestID = rsfResponse.getRequestID();
        try {
            //1.确定序列化器
            SerializeCoder coder = this.rsfEnv.getSerializeCoder(serializeType);
            if (coder == null) {
                String errorInfo = "do request(" + requestID + ") failed -> serializeType(" + serializeType + ") is undefined.";
                logger.error(errorInfo);
                ResponseInfo info = this.codecAdapter.buildResponseStatus(requestID, ProtocolStatus.SerializeForbidden, errorInfo);
                this.sendResponse(info);
                return;
            }
            //2.Response对象
            ResponseInfo info = codecAdapter.buildResponseInfo(rsfResponse);
            //
            this.sendResponse(info);
        } catch (Throwable e) {
            String errorInfo = "do request(" + requestID + ") failed -> serializeType(" + serializeType + ") ,serialize error: " + e.getMessage();
            logger.error(errorInfo, e);
            ResponseInfo info = this.codecAdapter.buildResponseStatus(requestID, ProtocolStatus.SerializeError, errorInfo);
            this.sendResponse(info);
        }
    }
    protected final void sendResponse(ResponseInfo info) {
        if (this.requestInfo.isMessage()) {
            return;/*如果是消息类型调用,则丢弃response*/
        }
        doSendResponse(info);
    }
    protected abstract void doSendResponse(ResponseInfo info);
}