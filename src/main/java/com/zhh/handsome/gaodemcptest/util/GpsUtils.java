package com.zhh.handsome.gaodemcptest.util;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Slf4j
@Component
public class GpsUtils {

    /**
     * 从图片输入流中提取经纬度
     * @param inputStream 图片输入流
     * @return GeoLocation 对象，如果未找到则返回 null
     */
    public GeoLocation extractGps(InputStream inputStream) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(inputStream);
            GpsDirectory gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gpsDirectory != null) {
                return gpsDirectory.getGeoLocation();
            }
        } catch (Exception e) {
            log.warn("无法从图片中提取GPS信息: {}", e.getMessage());
        }
        return null;
    }
}
