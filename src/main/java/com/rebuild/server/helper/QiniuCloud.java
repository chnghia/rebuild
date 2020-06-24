/*
Copyright (c) REBUILD <https://getrebuild.com/> and its owners. All rights reserved.

rebuild is dual-licensed under commercial and open source licenses (GPLv3).
See LICENSE and COMMERCIAL in the project root for license information.
*/

package com.rebuild.server.helper;

import cn.devezhao.commons.CalendarUtils;
import cn.devezhao.commons.CodecUtils;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Client;
import com.qiniu.http.Response;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;
import com.qiniu.util.StringMap;
import com.rebuild.server.Application;
import com.rebuild.server.RebuildException;
import com.rebuild.server.helper.cache.CommonCache;
import com.rebuild.utils.CommonsUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.Assert;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.UUID;

/**
 * 七牛云存储
 * 
 * @author zhaofang123@gmail.com
 * @since 05/19/2018
 */
public class QiniuCloud {

	private static final Log LOG = LogFactory.getLog(QiniuCloud.class);

    /**
     * 默认配置
     */
	public static final Configuration CONFIGURATION = new Configuration(Region.autoRegion());

	private final UploadManager UPLOAD_MANAGER = new UploadManager(CONFIGURATION);
	
	private Auth _auth;
	private String bucketName;

	private QiniuCloud() {
		String[] account = SysConfiguration.getStorageAccount();
		if (account != null) {
			this._auth = Auth.create(account[0], account[1]);
			this.bucketName = account[2];
		} else {
			LOG.warn("No QiniuCloud configuration! Using local storage.");
		}
	}
	
	/**
	 * 是否可用
	 *
	 * @return
	 */
	public boolean available() {
		return this._auth != null;
	}

	/**
	 * @return
	 */
	public Auth getAuth() {
		Assert.notNull(_auth, "云存储账户未配置");
		return _auth;
	}

	/**
	 * 文件上传
	 * 
	 * @param file
	 * @return
	 * @throws IOException
	 */
	public String upload(File file) throws IOException {
		String key = formatFileKey(file.getName());
		Response resp = UPLOAD_MANAGER.put(file, key, getAuth().uploadToken(bucketName));
		if (resp.isOK()) {
			return key;
		} else {
			LOG.error("文件上传失败 : " + resp);
			return null;
		}
	}
	
	/**
	 * 从 URL 上传文件
	 * 
	 * @param url
	 * @return
	 * @throws Exception
	 */
	public String upload(URL url) throws Exception {
		File tmp = SysConfiguration.getFileOfTemp("download." + System.currentTimeMillis());
		boolean success = CommonsUtils.readBinary(url.toString(), tmp);
		if (!success) {
			throw new RebuildException("无法从 URL 读取文件 : " + url);
		}
		
		try {
			return upload(tmp);
		} finally {
            FileUtils.deleteQuietly(tmp);
		}
	}
	
	/**
	 * 生成访问 URL
	 * 
	 * @param filePath
	 * @return
	 */
	public String url(String filePath) {
		return url(filePath, 60 * 15);
	}

	/**
	 * 生成访问 URL
	 * 
	 * @param filePath
	 * @param seconds 有效期
	 * @return
	 */
	public String url(String filePath, int seconds) {
		String baseUrl = SysConfiguration.getStorageUrl() + filePath;
		// default use HTTPS
		if (baseUrl.startsWith("//")) {
			baseUrl = "https:" + baseUrl;
		}

		long deadline = System.currentTimeMillis() / 1000 + seconds;
		// Use http cache
		seconds /= 2;
		deadline = deadline / seconds * seconds;
		return getAuth().privateDownloadUrlWithDeadline(baseUrl, deadline);
	}
	
	/**
	 * 删除文件
	 * 
	 * @param key
	 * @return
	 */
	protected boolean delete(String key) {
		BucketManager bucketManager = new BucketManager(getAuth(), CONFIGURATION);
		Response resp;
		try {
			resp = bucketManager.delete(this.bucketName, key);
			if (resp.isOK()) {
				return true;
			} else {
				throw new RebuildException("删除文件失败 : " + this.bucketName + " < " + key + " : " + resp.bodyString());
			}
		} catch (QiniuException e) {
			throw new RebuildException("删除文件失败 : " + this.bucketName + " < " + key, e);
		}
	}

	/**
	 * @param fileKey
	 * @return
	 * @see #formatFileKey(String)
	 */
	public String getUploadToken(String fileKey) {
		// 上传策略参见 https://developer.qiniu.com/kodo/manual/1206/put-policy
		StringMap policy = new StringMap().put("fsizeLimit", 102400000);  // 100M
		return getAuth().uploadToken(bucketName, fileKey, 60 * 10, policy);
	}

	/**
	 * 获取空间大小
	 *
	 * @return bytes
	 */
	@SuppressWarnings("deprecation")
	public long stats() {
		String time = CalendarUtils.getPlainDateFormat().format(CalendarUtils.now());
		String url = String.format(
				"%s/v6/space?bucket=%s&begin=%s000000&end=%s235959&g=day", CONFIGURATION.apiHost(), bucketName, time, time);
		StringMap headers = getAuth().authorization(url);

		try {
			Client client = new Client(CONFIGURATION);
			Response resp = client.get(url, headers);
			if (resp.isOK()) {
				JSONObject map = JSON.parseObject(resp.bodyString());
				return map.getJSONArray("datas").getLong(0);
			}

		} catch (QiniuException e) {
			LOG.warn(null, e);
		}
		return -1;
	}

	private static final QiniuCloud INSTANCE = new QiniuCloud();
	/**
	 * @return
	 */
	public static QiniuCloud instance() {
		return INSTANCE;
	}
	
	// --
	
	/**
	 * @param fileName
	 * @return
	 * @see #parseFileName(String)
	 */
	public static String formatFileKey(String fileName) {
		return formatFileKey(fileName, true);
	}
	
	/**
	 * @param fileName
	 * @param keepName
	 * @return
	 * @see #parseFileName(String)
	 */
	public static String formatFileKey(String fileName, boolean keepName) {
		if (!keepName) {
			String[] fileNameSplit = fileName.split("\\.");
			fileName = UUID.randomUUID().toString().replace("-", "");
			if (fileNameSplit.length > 1 && StringUtils.isNotBlank(fileNameSplit[fileNameSplit.length - 1])) {
				fileName += "." + fileNameSplit[fileNameSplit.length - 1];
			}
		} else {
			while (fileName.contains("__")) {
				fileName = fileName.replace("__", "_");
			}
			if (fileName.contains("+")) {
				fileName = fileName.replace("+", "");
			}
			if (fileName.contains("#")) {
				fileName = fileName.replace("#", "");
			}
			if (fileName.contains("?")) {
				fileName = fileName.replace("?", "");
			}
			if (fileName.length() > 41) {
				fileName = fileName.substring(0, 20) + "-" + fileName.substring(fileName.length() - 20);
			}
		}
		
		String datetime = CalendarUtils.getDateFormat("yyyyMMddHHmmssSSS").format(CalendarUtils.now());
		fileName = String.format("rb/%s/%s__%s", datetime.substring(0, 8), datetime.substring(8), fileName);
		return fileName;
	}
	
	/**
	 * 解析上传文件名称
	 * 
	 * @param filePath
	 * @return
	 * @see #formatFileKey(String)
	 */
	public static String parseFileName(String filePath) {
		String[] filePathSplit = filePath.split("/");
		String fileName = filePathSplit[filePathSplit.length - 1];
		if (fileName.contains("__")) {
			fileName = fileName.substring(fileName.indexOf("__") + 2);
		}
		return fileName;
	}
	
	/**
	 * URL 编码（中文或特殊字符）
	 * 
	 * @param url
	 * @return
	 */
	public static String encodeUrl(String url) {
		if (StringUtils.isBlank(url)) {
			return url;
		}
		
		String[] urlSplit = url.split("/");
		for (int i = 0; i < urlSplit.length; i++) {
			String e = CodecUtils.urlEncode(urlSplit[i]);
			if (e.contains("+")) {
				e = e.replace("+", "%20");
			}
			urlSplit[i] = e;
		}
		return StringUtils.join(urlSplit, "/");
	}

    /**
     * 存储空间大小（1小时缓存）
     *
     * @return
     */
    public static long storageSize() {
        Long size = (Long) Application.getCommonCache().getx("_StorageSize");
        if (size != null) {
            return size;
        }

        if (QiniuCloud.instance().available()) {
            size = QiniuCloud.instance().stats();
        } else {
            File data = SysConfiguration.getFileOfData("rb");
            size = FileUtils.sizeOfDirectory(data);
        }

        Application.getCommonCache().putx("_StorageSize", size, CommonCache.TS_HOUR);
        return size;
    }
}
