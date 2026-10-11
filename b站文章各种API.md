# 接口签名实现标准和状态码

```
签名
不是所有接口都需要实现该接口，本签名为特定接口的请求签名，如相关文档没注明需要公共签名或者文档有单独签名说明，请根据具体接口文档提示进行签名
请求规范
接入前请确保已入驻开放平台，且创建开放平台应用以获取client_id和app_secret
需使用B站侧提供的签名算法及header头信息进行返回；
加密时client_id和app_secret以B站侧提供的信息为准；

签名算法
公共参数
下表描述的公共请求头（Request Header）适用于通过URL发送HTTP请求调用API。

名称	类型	是否必填	描述
Accept	String	是	接受的返回结果的类型。目前只支持JSON类型。取值：application/json
Content-Type	String	是	当前请求体（Request Body）的数据类型。取值：application/json或multipart/form-data
x-bili-content-md5	String	是	请求体的编码值，根据请求体计算所得(全小写字母)。

算法说明：将body的请求体内容（除文件外）当作字符串进行MD5编码。
如果为GET请求或者请求的body为空，x-bili-content-md5的值请直接使用空字符串(string.Empty)进行求md5。
x-bili-timestamp	String	是	unix时间戳，单位是秒。请求时间戳不能超过当前时间10分钟，否则请求会被丢弃。
x-bili-signature-method	String	是	签名方式。取值：HMAC-SHA256
x-bili-signature-nonce	String	是	全网签名唯一随机数。用于防止网络重放攻击，建议您每一次请求都使用不同的随机数，例如GUID。
x-bili-accesskeyid	String	是	B站侧给出的Access Key(申请应用时获得的client_id)
x-bili-signature-version	String	是	如无单独说明，请取值2.0
access-token	String	否	x-bili-signature-version为2.0时必填，为通过OAuth2授权获取到的access_token
Authorization	String	是	请求签名。关于请求签名的计算方法，请参见签名机制。
签名算法
签名算法使用HMAC-SHA256

编码格式
请求必须为UTF-8编码

签名机制
1.构建完整的待签名字符串
抽取带”x-bili-“前缀的自定义header，按字典排序拼接，构建完整的待签名字符串：

请注意：
待签名字符串包含换行符\n
如果为GET请求或者请求的body为空，x-bili-content-md5的值请直接使用空字符串(string.Empty)进行求md5
"x-bili-accesskeyid:$accesskeyidValue"
"x-bili-content-md5:$contentMd5Value"
"x-bili-signature-method:HMAC-SHA256"
"x-bili-signature-nonce:$signatureNonceValue"
"x-bili-signature-version:2.0"
"x-bili-timestamp:$timestamp"
待签名字符串示例：

x-bili-accesskeyid:xxxx
x-bili-content-md5:fa6837e35b2f591865b288dfd859ce9d
x-bili-signature-method:HMAC-SHA256
x-bili-signature-nonce:ad184c09-095f-91c3-0849-230dd3744045
x-bili-signature-version:2.0
x-bili-timestamp:1624594467
2.生成签名
对步骤1中得到的字符串，使用app_secret进行HMAC-SHA256算法进行签名。

go示例代码如下：

func HmacSHA256(key string, data string) string {
    mac := hmac.New(sha256.New, []byte(key))
    mac.Write([]byte(data))
    return hex.EncodeToString(mac.Sum(nil))
}
将最后签名得到的字符串添加到Header的Authorization字段中

签名验证工具
点击下载工具
工具运行环境需要为Windows 64位环境

如果是其他系统环境，或上面下载的工具不可用，请到 Releases 进行下载

签名机制参考Demo
签名算法C#版本Demo

签名机制参考DEMO(Go)
package main
import (
    "crypto/hmac"
    "crypto/md5"
    "crypto/sha256"
    "encoding/hex"
    "encoding/json"
    "fmt"
    "github.com/monaco-io/request"
    "sort"
    "strconv"
    "strings"
    "time"
)
type BaseResp struct {
    Code      int64       `json:"code"`
    Message   string      `json:"message"`
    RequestId string      `json:"request_id"`
    Data      interface{} `json:"data"`
}
const (
    ClientIdProd    = "yourclientid"
    AppSecretProd   = "yoursecret"
    AccessTokenProd = "youraccesstoken"
)
const (
    AcceptHeader              = "Accept"
    ContentTypeHeader         = "Content-Type"
    AuthorizationHeader       = "Authorization"
    JsonType                  = "application/json"
    HmacSha256                = "HMAC-SHA256"
    BiliTimestampHeader       = "x-bili-timestamp"
    BiliSignatureMethodHeader = "x-bili-signature-method"
    BiliSignatureNonceHeader  = "x-bili-signature-nonce"
    BiliAccessKeyIdHeader     = "x-bili-accesskeyid"
    BiliSignVersionHeader     = "x-bili-signature-version"
    BiliContentMD5Header      = "x-bili-content-md5"
    AccessToken               = "access-token"
    BiliVersionV2             = "2.0"
    MainOpenPlatformHttpHost = "https://member.bilibili.com"
)
type CommonHeader struct {
    ContentType       string
    ContentAcceptType string
    Timestamp         string
    SignatureMethod   string
    SignatureVersion  string
    Authorization     string
    Nonce             string
    AccessKeyId       string
    ContentMD5        string
    X1BilispyColor    string
    AccessToken       string
}
// ApiRequestV2 http request demo方法
func ApiRequestV2(reqJson, requestUrl string) (resp BaseResp, err error) {
    resp = BaseResp{}
    header := &CommonHeader{
       AccessToken:       AccessTokenProd,
       ContentType:       JsonType,
       ContentAcceptType: JsonType,
       Timestamp:         strconv.FormatInt(time.Now().Unix(), 10),
       SignatureMethod:   HmacSha256,
       SignatureVersion:  BiliVersionV2,
       Authorization:     "",
       Nonce:             strconv.FormatInt(time.Now().UnixNano(), 10), //用于幂等,记得替换
       AccessKeyId:       ClientIdProd,
       ContentMD5:        Md5(reqJson),
       //X1BilispyColor:    model.Color,
    }
    header.Authorization = CreateSignature(header, AppSecretProd)
    cli := request.Client{
       Method: "POST",
       URL:    fmt.Sprintf("%s%s", MainOpenPlatformHttpHost, requestUrl),
       Header: ToMap(header),
       String: reqJson,
    }
    // 打印请求的cURL命令
    fmt.Println("cURL Command:")
    cli.PrintCURL()
    var respTest interface{}
    cliResp := cli.Send().Scan(&respTest)
    if !cliResp.OK() {
       err = fmt.Errorf("[error] req:%+v resp:%+v err:%+v", reqJson, resp, cliResp.Error())
    }
    // 使用json.MarshalIndent来格式化JSON数据
    jsonData, err := json.MarshalIndent(respTest, "", "    ")
    if err != nil {
       fmt.Println("Error marshaling JSON:", err)
       return
    }
    // 打印格式化后的JSON字符串
    fmt.Println(string(jsonData))
    return
}
// CreateSignature 生成Authorization加密串
func CreateSignature(header *CommonHeader, accessKeySecret string) string {
    sStr := ToSortedString(header)
    return HmacSHA256(accessKeySecret, sStr)
}
// Md5 md5加密
func Md5(str string) (md5str string) {
    data := []byte(str)
    has := md5.Sum(data)
    md5str = fmt.Sprintf("%x", has)
    return md5str
}
// HmacSHA256 HMAC-SHA256算法
func HmacSHA256(key string, data string) string {
    mac := hmac.New(sha256.New, []byte(key))
    mac.Write([]byte(data))
    return hex.EncodeToString(mac.Sum(nil))
}
// ToMap 所有字段转map<string, string>
func ToMap(h *CommonHeader) map[string]string {
    return map[string]string{
       BiliTimestampHeader:       h.Timestamp,
       BiliSignatureMethodHeader: h.SignatureMethod,
       BiliSignatureNonceHeader:  h.Nonce,
       BiliAccessKeyIdHeader:     h.AccessKeyId,
       BiliSignVersionHeader:     h.SignatureVersion,
       BiliContentMD5Header:      h.ContentMD5,
       AuthorizationHeader:       h.Authorization,
        ContentTypeHeader:         h.ContentType,
       AcceptHeader:              h.ContentAcceptType,
       AccessToken: h.AccessToken,
    }
}
// ToSortMap 参与加密的字段转map<string, string>
func ToSortMap(h *CommonHeader) map[string]string {
    return map[string]string{
       BiliTimestampHeader:       h.Timestamp,
       BiliSignatureMethodHeader: h.SignatureMethod,
       BiliSignatureNonceHeader:  h.Nonce,
       BiliAccessKeyIdHeader:     h.AccessKeyId,
       BiliSignVersionHeader:     h.SignatureVersion,
       BiliContentMD5Header:      h.ContentMD5,
    }
}
// ToSortedString 生成需要加密的文本
func ToSortedString(h *CommonHeader) (sign string) {
    hMap := ToSortMap(h)
    var hSil []string
    for k := range hMap {
       hSil = append(hSil, k)
    }
    sort.Strings(hSil)
    for _, v := range hSil {
       sign += v + ":" + hMap[v] + "\n"
    }
    sign = strings.TrimRight(sign, "\n")
    return
}
第三方平台约定返回结构体
名称	类型	是否必填	例子	描述
code	int	是	0	返回码，业务错误码参考下方。
message	string	是	success	返回信息，业务错误信息参考下方。
data	object	是		业务信息。
request_id	string	是	abcd123456	每次请求生成的唯一id，用于问题排查。
状态码
状态码列表
请求通用错误
错误码	含义
4000	参数错误(一般是缺少参数)
4001	配置无效
4002	签名异常
4003	请求过期
4004	重复请求
4005	签名method异常
4006	签名版本异常
4007	Content-Type不为application/json
4008	MD5校验失败
4009	Accept不为application/json
4010	服务异常
4011	内部错误
4012	BizCode不支持该方法
OAuth2：
错误码	含义
122000	client_id错误
122001	client_secret 错误
122002	code未找到
122007	refreshToken不合法
122008	app_id不匹配
122009	系统繁忙，获取用户数据失败，请稍后再试
122010	系统异常，相关用户操作失败
鉴权与限频限流：
错误码	含义
127000	缺少鉴权参数
127001	access_token验证错误
127002	sign验证错误
127003	缺少mid或mid不匹配
127004	client_id验证错误
127005	机构认证未通过
127006	应用认证未通过
127007	应用无该接口权限
127008	mid验证失败
127009	接口请求次数达到上限
127010	sign白名单验证错误
127011	该接口用户未授权
127022	upload_token验证错误
127023	client_token校验错误
错误码	含义
127009	接口繁忙，请稍后再试
127304	接口访问受限，请确认应用已申请相关权限，且授权账号状态正常
127305	白名单限制
127306	接口请求频率过高，请确保请求量正常。如果使用量大请联系运营进行相关业务咨询

```










# 文章提交

```
接口说明
调用该接口完成文章稿件投递
若需要传入的图片url，请传入经过【图片上传】接口生成的url
文章稿件提交之后会存在审核过程，期间不对外开放
请求url
https://member.bilibili.com/arcopen/fn/article/add

请求方式
POST

请求头
请求增加公共请求头（Request Header），签名版本为2.0，详情请查看 接口签名说明 文档

请求参数
url参数
​ 无

body参数

参数名	必传	类型	备注
title	true	string	文章标题，建议30字以内，最多不超过40字
category	true	int	文章分类，通过categories接口获取，填写子分类id
template_id	true	int	选择稿件模板： 3-封面三图； 4-封面单图或无封面图且banner_url/top_video_bvid不为空； 5-封面无图且banner_url/top_video_bvid都为空，平台生成默认封面
summary	true	string	文章简介，字数不少于200字
content	true	string	正文内容，字数200-40000，或者至少添加三张图片 添加图片模板：<figure class="img-box" contenteditable="false"><img src="${图片url}"><figcaption class="caption" contenteditable="">${图片描述信息}</figcaption></figure> 添加卡片模板：从卡片信息接口获取
banner_url	false	string	头图url，与top_video_bvid可都不填写，或者只能二选一 推荐上传960 x 540像素图片，超过此像素会自动居中剪裁
original	false	int	是否原创：0-非原创 1-原创 默认0
image_urls	false	string	封面默认使用正文文字图片，多个图片用英文逗号分割 图片url为通过图片上传接口返回的url地址 若单封面图，推荐上传640x188像素图片（非Gif图），超过此像素会自动居中剪裁 若三封面图，正文需存在至少3张尺寸为188 x 188像素图片（非Gif图），超过此像素会自动居中剪裁，对应的图片url为本字段的图片url。
tags	false	string	用户自定义标签，多标签情况下用英文逗号分割
list_id	false	int	文集id
up_closed_reply	false	int	是否up主关闭评论区：0-否 1-是 默认0
top_video_bvid	false	string	头部视频bvid，与banner_url可都不填写，或者只能二选一



请求样例
curl --location --request POST 'https://member.bilibili.com/arcopen/fn/article/add' \
--header 'Accept: application/json' \
--header 'Access-Token: a24e56a0fc0093668bca0f3a15410ea2' \
--header 'Authorization: 950a03adb287904a93bcffd7e4abf57a4c2ef0608bf1f95f3bcb2c278ae532be' \
--header 'X-Bili-Accesskeyid: 93a0f774fae84e6c' \
--header 'X-Bili-Content-Md5: d41d8cd98f00b204e9800998ecf8427e' \
--header 'X-Bili-Signature-Method: HMAC-SHA256' \
--header 'X-Bili-Signature-Nonce: 1730185575446751000' \
--header 'X-Bili-Signature-Version: 2.0' \
--header 'X-Bili-Timestamp: 1730185575' \
--header 'Host: member.bilibili.com' \
--header 'Connection: keep-alive' \
--header 'Content-Type: multipart/form-data; boundary=--------------------------690347605097214386959433' \
--form 'title="开放平台测试标题"' \
--form 'category="1"' \
--form 'template_id="5"' \
--form 'summary="注意简介字数"' \
--form 'content="开放平台正文测试"' \








响应参数
body参数
参数名	说明	备注
id	int	新建的文章id
响应样例
{
    "code": 0,
    "data": {
        "id": 39633826
    },
    "message": "0",
    "request_id": "6974e5f054ef5a112c9a9e6e1f6721e6",
    "ttl": 1
}

```







# 文章编辑

```
接口说明
调用该接口可完成文章编辑
文章编辑后，需重新进行审核
注意：请求body参数需要将所有字段传递过来，不需要修改的字段传原值。建议编辑前使用【查询文章详情】接口获取原值
请求URL
https://member.bilibili.com/arcopen/fn/article/edit

请求方式
POST

请求头
请求增加公共请求头（Request Header），签名版本为2.0，详情请查看 接口签名说明 文档

请求参数
url参数
​ 无

body参数
在【文章提交】接口基础上增加一个id参数：

参数名	必传	类型	备注
id	true	int64	文章id
请求样例
curl --location --request POST 'https://member.bilibili.com/arcopen/fn/article/edit' \
--header 'Accept: application/json' \
--header 'Access-Token: a24e56a0fc0093668bca0f3a15410ea2' \
--header 'Authorization: 950a03adb287904a93bcffd7e4abf57a4c2ef0608bf1f95f3bcb2c278ae532be' \
--header 'X-Bili-Accesskeyid: 93a0f774fae84e6c' \
--header 'X-Bili-Content-Md5: d41d8cd98f00b204e9800998ecf8427e' \
--header 'X-Bili-Signature-Method: HMAC-SHA256' \
--header 'X-Bili-Signature-Nonce: 1730185575446751000' \
--header 'X-Bili-Signature-Version: 2.0' \
--header 'X-Bili-Timestamp: 1730185575' \
--header 'Host: member.bilibili.com' \
--header 'Connection: keep-alive' \
--header 'Content-Type: multipart/form-data; boundary=--------------------------690347605097214386959433' \
--form 'id="39633826"' \
--form 'title="开放平台测试标题"' \
--form 'category="1"' \
--form 'template_id="5"' \
--form 'summary="注意简介字数"' \
--form 'content="开放平台正文测试"' \
响应参数
响应样例
{
    "code": 0,
    "message": "0",
    "request_id": "5bf1070a43a59d8836a729bad06721eb",
    "ttl": 1
}

```


# 文章删除

```
接口说明
调用该接口可完成文章删除
删除过程不可逆，请谨慎操作
请求URL
https://member.bilibili.com/arcopen/fn/article/delete

请求方式
POST

请求头
请求增加公共请求头（Request Header），签名版本为2.0，详情请查看 接口签名说明 文档

请求参数
url参数
​ 无

body参数
参数名	必传	类型	备注
ids	true	array	文章id；若要同时删除多篇文章，多个id用英文逗号分割
请求样例
curl --location --request POST 'https://member.bilibili.com/arcopen/fn/article/delete' \
--header 'Accept: application/json' \
--header 'Access-Token: a24e56a0fc0093668bca0f3a15410ea2' \
--header 'Authorization: 950a03adb287904a93bcffd7e4abf57a4c2ef0608bf1f95f3bcb2c278ae532be' \
--header 'X-Bili-Accesskeyid: 93a0f774fae84e6c' \
--header 'X-Bili-Content-Md5: d41d8cd98f00b204e9800998ecf8427e' \
--header 'X-Bili-Signature-Method: HMAC-SHA256' \
--header 'X-Bili-Signature-Nonce: 1730185575446751000' \
--header 'X-Bili-Signature-Version: 2.0' \
--header 'X-Bili-Timestamp: 1730185575' \
--header 'Host: member.bilibili.com' \
--header 'Connection: keep-alive' \
--header 'Content-Type: multipart/form-data; boundary=--------------------------690347605097214386959433' \
--form 'ids="5678,123"'
响应参数
响应样例
{
    "code": 0,
    "message": "0",
    "request_id": "5bf1070a43a59d8836a729bad06721eb",
    "ttl": 1
}

```





# 文章查询


```
接口说明
调用该接口可以查看文章详情信息
请求URL
https://member.bilibili.com/arcopen/fn/article/detail

请求方式
GET

请求头
请求增加公共请求头（Request Header），签名版本为2.0，详情请查看 接口签名说明 文档

请求参数
url参数
​ 无

body参数
参数名	必传	类型	备注
id	true	int	文章id
请求样例
curl --location --request GET 'https://member.bilibili.com/arcopen/fn/article/detail' \
--header 'Accept: application/json' \
--header 'Access-Token: a24e56a0fc0093668bca0f3a15410ea2' \
--header 'Authorization: 950a03adb287904a93bcffd7e4abf57a4c2ef0608bf1f95f3bcb2c278ae532be' \
--header 'X-Bili-Accesskeyid: 93a0f774fae84e6c' \
--header 'X-Bili-Content-Md5: d41d8cd98f00b204e9800998ecf8427e' \
--header 'X-Bili-Signature-Method: HMAC-SHA256' \
--header 'X-Bili-Signature-Nonce: 1730185575446751000' \
--header 'X-Bili-Signature-Version: 2.0' \
--header 'X-Bili-Timestamp: 1730185575' \
--header 'Host: member.bilibili.com' \
--header 'Connection: keep-alive' \
--header 'Content-Type: multipart/form-data; boundary=--------------------------690347605097214386959433' \
--form 'id="5678"'
响应参数
body参数
请参考【获取专栏数据】接口body参数

响应样例
{
    "code": 0,
    "message": "0",
    "ttl": 1,
    "data": {
        "content": "专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试",
        "id": 5678,
        "category": {
            "id": 3,
            "parent_id": 0,
            "name": "生活"
        },
        "title": "专栏投稿测试1",
        "summary": "专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投稿测试专栏投",
        "banner_url": "https://i0.hdslb.com/bfs/article/cc40b87d32b272d84c01426aa83e5cc3e8238e68.jpg",
        "template_id": 3,
        "state": 0,
        "image_urls": [
            "https://i0.hdslb.com/bfs/article/cc40b87d32b272d84c01426aa83e5cc3e8238e68.jpg",
            "https://i0.hdslb.com/bfs/article/49a43ff0a68d9b8ba035fd35510989b97a203c7e.jpg",
            "https://i0.hdslb.com/bfs/article/90ad18c6205054ec8af8b809ada4e2b90029ac1c.jpg"
        ],
        "publish_time": 1641293385,
        "ctime": 1640939047,
        "stats": {
            "view": 0,
            "favorite": 0,
            "like": 0,
            "dislike": 0,
            "reply": 0,
            "share": 0,
            "coin": 0
        },
        "tags": [
            {
                "tid": 600,
                "name": "测试"
            },
            {
                "tid": 189341,
                "name": "专栏"
            }
        ],
        "reason": "",
        "words": 0,
        "list": {
            "id": 462,
            "name": "文集1",
            "image_url": "",
            "update_time": 1641359180,
            "ctime": 1639654241,
            "publish_time": 1641295677,
            "summary": "文集简介",
            "words": 248
        },
          "original": 0,
        "top_video_bvid": "",
        "type": 0
    }
}

```




# 查询文章列表


```
接口说明
调用该接口可以获取该up主投稿的所有文章
请求URL
https://member.bilibili.com/arcopen/fn/article/list

请求方式
GET

请求头
请求增加公共请求头（Request Header），签名版本为2.0，详情请查看 接口签名说明 文档

请求参数
url参数
​ 无

body参数
参数名	必传	类型	备注
pn	false	int	页码，默认1
ps	false	int	每页大小，默认10
sort	false	int	排序类型：1 默认（创建时间倒序）2 喜欢 3 评论 4浏览 5收藏 6硬币
group	false	int	默认0，0-全部（除了草稿和已删除）1-进行中 2-已通过 3-未通过
category	false	int	分类id
请求样例
curl --location --request GET 'https://member.bilibili.com/arcopen/fn/article/list' \
--header 'Accept: application/json' \
--header 'Access-Token: a24e56a0fc0093668bca0f3a15410ea2' \
--header 'Authorization: 950a03adb287904a93bcffd7e4abf57a4c2ef0608bf1f95f3bcb2c278ae532be' \
--header 'X-Bili-Accesskeyid: 93a0f774fae84e6c' \
--header 'X-Bili-Content-Md5: d41d8cd98f00b204e9800998ecf8427e' \
--header 'X-Bili-Signature-Method: HMAC-SHA256' \
--header 'X-Bili-Signature-Nonce: 1730185575446751000' \
--header 'X-Bili-Signature-Version: 2.0' \
--header 'X-Bili-Timestamp: 1730185575' \
--header 'Host: member.bilibili.com' \
--header 'Connection: keep-alive' \
--header 'Content-Type: multipart/form-data; boundary=--------------------------690347605097214386959433' \
--data-raw '{
    "pn" :  1,
    "ps" : 1
}'
响应参数
body参数
参数名	说明	备注
id	int	文章id
category.id	int	分类id
category.parent_id	int	父级分类id
category.name	string	分类名称
title	string	文章标题
summary	string	文章简介
banner_url	string	文章头图
template_id	int	模板类型
state	int	文章状态
reason	string	文章打回理由
image_urls	[]string	封面图
publish_time	int	文章发表时间
ctime	int	文章创建时间戳
states.view	int	浏览数
states.favorite	int	收藏数
states.like	int	喜欢数
states.dislike	int	不喜欢数
states.reply	int	评论数
states.share	int	分享数
states.coin	int	硬币数
words	int	字数
list.id	int	文集id
list.name	string	文集名称
list.image_url	string	文集封面图片
list.summary	string	文件简介
list.word	int	文集总字数
list.publish_time	int	最后发表文章时间戳
list.ctime	int	文集创建时间戳
list.update_time	int	最后修改时间戳
apply_time	string	申请时间
top_video_bvid	string	头部视频bvid
all	int	所有文章数
audit	int	审核中的文章数
passed	int	通过的文章数
notPassed	int	未通过的文章数
type	int	0-老编辑器 1-笔记 2-新编辑器
稿件状态
状态值	说明
状态值	说明
-11	自动锁定
-10	锁定
-8	定时发布待审
-9	定时发布审核通过待发布
-1	定时发布重新编辑审核通过待发布
-3	打回
-2	提交待审
0	开放浏览
2	发布后打回修改再提交
3	发布后打回
4	先发后审
5	重复编辑待审
6	重复编辑未通过
7	重复编辑通过
9	定时发布通过已发布
13	定时发布重新编辑通过已发布
-13	定时发布重新编辑审核通过待发布
-12	定时待发重复编辑待审
-14	定时待发重复编辑审核不通过
14	定时待发重复编辑审核不通过以发布
12	定时待发重复编辑审核审核中以发布
响应样例
{
    "code": 0,
    "message": "0",
    "ttl": 1,
    "data": {
        "articles": [
            {
                "id": 5692,
                "category": {
                    "id": 1,
                    "parent_id": 0,
                    "name": "游戏"
                },
                "title": "文章投稿1",
                "summary": "文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容",
                "banner_url": "",
                "template_id": 5,
                "state": -2,
                "image_urls": [
                    "https://i0.hdslb.com/bfs/article/banner/50793a8446b29bd90d38c0742f263f46fa40cf51.png"
                ],
                "publish_time": 1641295677,
                "ctime": 1641295602,
                "stats": {
                    "view": 1,
                    "favorite": 0,
                    "like": 0,
                    "dislike": 0,
                    "reply": 0,
                    "share": 0,
                    "coin": 0
                },
                "reason": "",
                "words": 204,
                "list": {
                    "id": 462,
                    "name": "testUpdate1",
                    "image_url": "",
                    "update_time": 1640075981,
                    "ctime": 1639654241,
                    "publish_time": 1641295677,
                    "summary": "testsummary1",
                    "words": 248
                },
                "apply_time": "2022-01-04T19:26:42+08:00",
                  "original": 0,
                "top_video_bvid": "BV1WN4y147wW",
                "type": 0
            },
            {
                "id": 5678,
                "category": {
                    "id": 3,
                    "parent_id": 0,
                    "name": "生活"
                },
                "title": "文章投稿测试2",
                "summary": "文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容文章投稿内容",
                "banner_url": "https://i0.hdslb.com/bfs/article/cc40b87d32b272d84c01426aa83e5cc3e8238e68.jpg",
                "template_id": 3,
                "state": -10,
                "image_urls": [
                    "https://i0.hdslb.com/bfs/article/cc40b87d32b272d84c01426aa83e5cc3e8238e68.jpg",
                    "https://i0.hdslb.com/bfs/article/49a43ff0a68d9b8ba035fd35510989b97a203c7e.jpg",
                    "https://i0.hdslb.com/bfs/article/90ad18c6205054ec8af8b809ada4e2b90029ac1c.jpg"
                ],
                "publish_time": 1641293385,
                "ctime": 1640939047,
                "stats": {
                    "view": 0,
                    "favorite": 0,
                    "like": 0,
                    "dislike": 0,
                    "reply": 0,
                    "share": 0,
                    "coin": 0
                },
                "reason": "经举报确认，该稿件涉及抄袭行为，故撤下并予以锁定",
                "words": 2254,
                "list": {
                    "id": 462,
                    "name": "文集1",
                    "image_url": "",
                    "update_time": 1640075981,
                    "ctime": 1639654241,
                    "publish_time": 1641295677,
                    "summary": "文集简介1",
                    "words": 2458
                },
                "apply_time": "2021-12-31T16:24:07+08:00",
                  "original": 0,
                "top_video_bvid": "",
                "type": 0
            }
        ],
        "creationArtsType": {
            "all": 2,
            "audit": 1,
            "passed": 0,
            "notPassed": 1 
        },
        "artPage": {
            "pn": 1,
            "ps": 10,
            "total": 2
        }
    }
}

```




# 专栏稿件图片上传

```
接口说明
调用该接口可以上传普通图片和带有bilibi标志和up主昵称的水印图片
支持格式jpg、png，文件大小≤5MB
请求URL
https://member.bilibili.com/arcopen/fn/article/upload/image

请求方式
POST

请求头
请求增加公共请求头（Request Header），签名版本为2.0，详情请查看 接口签名说明 文档

请求参数
url参数
无

body参数
参数名	必传	类型	备注
file	true	file	图片文件
watermark	false	bool	是否带水印；true-带水印，false-不带水印，默认false
请求样例
curl --location --request POST 'https://member.bilibili.com/arcopen/fn/article/upload/image' \
--header 'Accept: application/json' \
--header 'x-bili-timestamp: 1712473117' \
--header 'x-bili-signature-method: HMAC-SHA256' \
--header 'x-bili-signature-version: 2.0' \
--header 'Authorization: d41d8cd98f00b204e9800998ecf8427e' \
--header 'x-bili-signature-nonce: 1712473117993237000' \
--header 'x-bili-accesskeyid: 8ca079bc20ce433d' \
--header 'x-bili-content-md5: 795a2d7e13f989cee460801379fe543c' \
--header 'Connection: keep-alive' \
--header 'Host: member.bilibili.com' \
--header 'Content-Type: multipart/form-data; boundary=--------------------------044892437502502901765117' \
--form 'file=@"/Users/shenyue/Downloads/test.jpg"' \
--form 'watermark="${是否带水印}"'
响应参数
响应样例
{
    "code": 0,
    "message": "0",
    "ttl": 1,
    "data": {
        "url": "http://article.biliimg.com/bfs/article/90ad18c6205054ec8af8b809ada4e2b90029ac1c.jpg",
        "size": 39589
    }
}

```