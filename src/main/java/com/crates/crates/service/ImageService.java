package com.crates.crates.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ImageService {

    /** 브라우저가 이미지를 직접 받는 주소. 로컬은 MinIO 버킷, 운영은 CloudFront의 /images 경로다. */
    @Value("${storage.bucket-url}")
    private String bucketUrl;

    public String getImageUrl(String s3ObjectKey, String extension) {
        // 시딩 상한(seed.image-upload.limit) 때문에 이미지가 올라가지 않은 콘텐츠가 남는다.
        // 그대로 조립하면 ".../null.null" 같은 URL이 나가므로 null을 그대로 돌려준다.
        if (s3ObjectKey == null || extension == null) {
            return null;
        }
        return bucketUrl + "/" + s3ObjectKey + "." + extension;
    }
}
