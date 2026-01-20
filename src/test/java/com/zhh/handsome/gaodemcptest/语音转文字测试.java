package com.zhh.handsome.gaodemcptest;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.tomcat.util.file.ConfigurationSource;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

public class 语音转文字测试 {
    private static final String BASE_URL = "https://upload-ost-api.xfyun.cn";
    private static final String INIT_UPLOAD_URL = BASE_URL + "/v2/file/init";
    private static final String UPLOAD_SLICE_URL = BASE_URL + "/v2/file/slice";
    private static final String FINISH_UPLOAD_URL = BASE_URL + "/v2/file/finish";
    private static final String CREATE_TASK_URL = "https://iat-api.xfyun.cn/v2/ost";
    private static final String QUERY_TASK_URL = "https://api.xfyun.cn/v1/service/v1/ats";

    private static final String APP_ID = "4CC5779A"; // 请替换为你的实际APP ID
    private static final String API_KEY = "00fcb30884ee773b118c3d84c10b99a6"; // 请替换为你的实际API KEY
    private static final String API_SECRET = "MzhjOGU1ZmVjZTkzYTY3MDdhNDhlZTZi"; // 请替换为你的实际API SECRET

    @Test
    public void test() throws Exception {
        String audioFilePath = "path/to/your/audio/file.pcm"; // 替换为实际音频文件路径
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16); // 生成16位请求ID

        // 开始分块上传
        String uploadId = initUpload(requestId);
        if (uploadId != null) {
            uploadFileInChunks(audioFilePath, uploadId, requestId);
            String fileUrl = finishUpload(uploadId, requestId);

            if (fileUrl != null) {
                // 创建转写任务
                String taskId = createTranscriptionTask(fileUrl, requestId);
                if (taskId != null) {
                    // 查询任务状态
                    queryTaskStatus(taskId);
                } else {
                    System.out.println("Failed to create transcription task.");
                }
            } else {
                System.out.println("Failed to finish upload.");
            }
        } else {
            System.out.println("Failed to initialize upload.");
        }
    }

    // 初始化分块上传
    private String initUpload(String requestId) throws Exception {
        String url = INIT_UPLOAD_URL;
        Map<String, Object> params = Map.of(
            "request_id", requestId,
            "app_id", APP_ID
        );

        String response = sendPostRequestWithAuth(url, params, "/v2/file/init");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> responseMap = mapper.readValue(response, Map.class);

        if ((Integer) responseMap.get("code") == 0) {
            Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
            String uploadId = (String) data.get("upload_id");
            System.out.println("Upload initialized, upload_id: " + uploadId);
            return uploadId;
        } else {
            System.out.println("Init upload failed: " + response);
            return null;
        }
    }

    // 分块上传文件
    private void uploadFileInChunks(String filePath, String uploadId, String requestId) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("Audio file does not exist: " + filePath);
            return;
        }

        // 设置分块大小（这里设为1MB，可根据实际情况调整）
        long chunkSize = 1024 * 1024; // 1MB
        long fileSize = file.length();
        long totalChunks = (long) Math.ceil((double) fileSize / chunkSize);

        try (FileInputStream fis = new FileInputStream(file)) {
            for (int i = 0; i < totalChunks; i++) {
                long start = i * chunkSize;
                long end = Math.min(start + chunkSize, fileSize);
                byte[] chunkData = new byte[(int) (end - start)];
                
                fis.skip(start);
                int bytesRead = fis.read(chunkData);
                
                if (bytesRead > 0) {
                    // 发送单个分块
                    sendChunkRequest(filePath, uploadId, requestId, i, chunkData);
                    System.out.println("Uploaded chunk " + (i + 1) + "/" + totalChunks);
                }
            }
        }
    }

    // 发送单个分块
    private void sendChunkRequest(String filePath, String uploadId, String requestId, int sliceId, byte[] chunkData) throws Exception {
        String url = UPLOAD_SLICE_URL;
        
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(url);
        
        // 添加认证头部
        String date = getCurrentGMTDate();
        String authHeader = buildAuthorizationHeader(date, "", "/v2/file/slice");
        httpPost.setHeader("Authorization", authHeader);
        httpPost.setHeader("Date", date);
        
        // 构建multipart表单
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addBinaryBody("data", chunkData, ContentType.APPLICATION_OCTET_STREAM, filePath.substring(filePath.lastIndexOf("/") + 1));
        builder.addTextBody("app_id", APP_ID);
        builder.addTextBody("request_id", requestId);
        builder.addTextBody("upload_id", uploadId);
        builder.addTextBody("slice_id", String.valueOf(sliceId));

        HttpEntity entity = builder.build();
        httpPost.setEntity(entity);

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseBody = EntityUtils.toString(response.getEntity());
            System.out.println("Chunk upload response: " + responseBody);
            
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> responseMap = mapper.readValue(responseBody, Map.class);
            
            if ((Integer) responseMap.get("code") != 0) {
                System.out.println("Chunk upload failed: " + responseBody);
            }
        }
    }

    // 完成分块上传
    private String finishUpload(String uploadId, String requestId) throws Exception {
        String url = FINISH_UPLOAD_URL;
        Map<String, Object> params = Map.of(
            "request_id", requestId,
            "app_id", APP_ID,
            "upload_id", uploadId
        );

        String response = sendPostRequestWithAuth(url, params, "/v2/file/finish");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> responseMap = mapper.readValue(response, Map.class);

        if ((Integer) responseMap.get("code") == 0) {
            Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
            String fileUrl = (String) data.get("url");
            System.out.println("Upload finished, file URL: " + fileUrl);
            return fileUrl;
        } else {
            System.out.println("Finish upload failed: " + response);
            return null;
        }
    }

    // 创建转写任务
    private String createTranscriptionTask(String audioUrl, String requestId) throws Exception {
        Map<String, Object> request = Map.of(
            "common", Map.of("app_id", APP_ID),
            "business", Map.of(
                "request_id", requestId,
                "language", "zh_cn",
                "domain", "pro_ost_ed",
                "accent", "mandarin"
            ),
            "data", Map.of(
                "audio_url", audioUrl,
                "audio_src", "http",
                "format", "audio/L16;rate=16000",
                "encoding", "raw"
            )
        );

        String requestBody = new ObjectMapper().writeValueAsString(request);
        String url = CREATE_TASK_URL;
        
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(url);
        
        // 添加认证头部
        String date = getCurrentGMTDate();
        String authHeader = buildAuthorizationHeader(date, requestBody, "/v2/ost");
        httpPost.setHeader("Authorization", authHeader);
        httpPost.setHeader("Content-Type", "application/json; charset=utf-8");
        httpPost.setHeader("Host", "iat-api.xfyun.cn");
        httpPost.setHeader("Date", date);
        
        // 设置请求体
        org.apache.http.entity.StringEntity stringEntity = new org.apache.http.entity.StringEntity(requestBody, "UTF-8");
        httpPost.setEntity(stringEntity);

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseBody = EntityUtils.toString(response.getEntity());
            System.out.println("Create task response: " + responseBody);
            
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> responseMap = mapper.readValue(responseBody, Map.class);
            
            if ((Integer) responseMap.get("code") == 0) {
                Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
                String taskId = (String) data.get("task_id");
                System.out.println("Task created, task ID: " + taskId);
                return taskId;
            } else {
                System.out.println("Create task failed: " + responseBody);
                return null;
            }
        }
    }

    // 查询任务状态
    private void queryTaskStatus(String taskId) throws Exception {
        Map<String, Object> request = Map.of(
            "common", Map.of("app_id", APP_ID),
            "business", Map.of("task_id", taskId)
        );

        String requestBody = new ObjectMapper().writeValueAsString(request);
        String url = QUERY_TASK_URL;
        
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(url);
        
        // 添加认证头部
        String date = getCurrentGMTDate();
        String authHeader = buildAuthorizationHeader(date, requestBody, "/v1/service/v1/ats");
        httpPost.setHeader("Authorization", authHeader);
        httpPost.setHeader("Content-Type", "application/json; charset=utf-8");
        httpPost.setHeader("Host", "api.xfyun.cn");
        httpPost.setHeader("Date", date);
        
        // 设置请求体
        org.apache.http.entity.StringEntity stringEntity = new org.apache.http.entity.StringEntity(requestBody, "UTF-8");
        httpPost.setEntity(stringEntity);

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseBody = EntityUtils.toString(response.getEntity());
            System.out.println("Query task response: " + responseBody);
            
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> responseMap = mapper.readValue(responseBody, Map.class);
            
            if ((Integer) responseMap.get("code") == 0) {
                Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
                String taskStatus = (String) data.get("task_status");
                System.out.println("Task status: " + taskStatus);
                
                // 如果任务已完成（状态3或4），打印识别结果
                if ("3".equals(taskStatus) || "4".equals(taskStatus)) {
                    Map<String, Object> result = (Map<String, Object>) data.get("result");
                    if (result != null) {
                        System.out.println("Recognition result: " + result);
                    }
                }
            } else {
                System.out.println("Query task failed: " + responseBody);
            }
        }
    }

    // 发送带认证的POST请求
    private String sendPostRequestWithAuth(String url, Map<String, Object> params, String path) throws Exception {
        String jsonParams = new ObjectMapper().writeValueAsString(params);
        
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(url);
        
        // 添加认证头部
        String date = getCurrentGMTDate();
        String authHeader = buildAuthorizationHeader(date, jsonParams, path);
        httpPost.setHeader("Authorization", authHeader);
        httpPost.setHeader("Content-Type", "application/json; charset=utf-8");
        httpPost.setHeader("Host", getHostFromUrl(url));
        httpPost.setHeader("Date", date);
        
        // 设置请求体
        org.apache.http.entity.StringEntity stringEntity = new org.apache.http.entity.StringEntity(jsonParams, "UTF-8");
        httpPost.setEntity(stringEntity);

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            return EntityUtils.toString(response.getEntity());
        }
    }

    // 构建认证头部
    private String buildAuthorizationHeader(String date, String body, String path) throws Exception {
        // 计算body的SHA-256摘要
        byte[] bodyBytes = body.getBytes("UTF-8");
        byte[] digestBytes = MessageDigest.getInstance("SHA-256").digest(bodyBytes);
        String digest = "SHA-256=" + Base64.getEncoder().encodeToString(digestBytes);
        
        // 构建签名原串
        String host = getHostFromUrl(BASE_URL); // 或根据具体URL获取主机名
        String requestLine = "POST " + path + " HTTP/1.1";
        String signatureOrigin = "host: " + host + "\n" +
                                "date: " + date + "\n" +
                                requestLine + "\n" +
                                "digest: " + digest;
        
        // 使用hmac-sha256算法签名
        SecretKeySpec secretKeySpec = new SecretKeySpec(API_SECRET.getBytes("UTF-8"), "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(secretKeySpec);
        byte[] signatureSha = mac.doFinal(signatureOrigin.getBytes("UTF-8"));
        
        // base64编码得到最终signature
        String signature = Base64.getEncoder().encodeToString(signatureSha);
        
        // 拼接authorization字符串
        return String.format("api_key=\"%s\",algorithm=\"hmac-sha256\",headers=\"host date request-line digest\",signature=\"%s\"",
                API_KEY, signature);
    }

    // 获取当前GMT日期
    private String getCurrentGMTDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(new Date());
    }

    // 从URL提取主机名
    private String getHostFromUrl(String url) {
        int start = url.indexOf("//") + 2;
        int end = url.indexOf('/', start);
        if (end == -1) {
            end = url.length();
        }
        return url.substring(start, end);
    }
}