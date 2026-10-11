统一说明鉴权方式

AI开放能力目前支持三种鉴权认证机制，分别支持的功能范围和适用的用户群体如下：

鉴权机制	支持的功能范围	适用群体
access_token鉴权机制	AI开放能力的所有能力，包括公有云接口和离线SDK
不支持AI开放能力之外的产品	仅使用AI开放能力的客户
API Key鉴权机制	AI开放能力的公有云接口，暂不支持离线SDK
同时支持大模型服务与开发平台ModelBuilder、大模型应用开发平台AppBuilder的接口调用	要同时使用AI开放能力、ModelBuilder和AppBuilder的客户；
或此前在其他平台使用过大模型服务，现在迁移到百度智能云的客户，使用API Key可无缝迁移
Access Key ID/Secret Access Key鉴权机制	AI开放能力的公有云接口，暂不支持离线SDK
同时支持百度智能云大多数基础云产品，例如云服务器、对象存储等	同时使用基础云产品和AI开放能力的客户
需要注意，三种鉴权机制选择其中一种即可，不能在一次请求中同时使用多种鉴权机制。

以下为您分别介绍三种鉴权机制使用方式。

一、access_token鉴权机制
Access_token是用户的访问令牌，承载了用户的身份、权限等信息。鉴权主要分为以下两步：

1.获取AK/SK

2.获取Access_token

代码形式→适用于有计算机基础的用户
网页调试工具→适用于零基础的用户
在线调试工具（推荐）→快速调试接口效果
1. 获取AK/SK
当您成功创建应用后，在对应产品页签下选择“应用列表”，可查看已创建的应用。

平台将会分配给您此应用的相关凭证，主要为AppID、API Key、Secret Key。以上三个信息是您应用实际开发的重要凭证，每个应用各不相同，为了您的财产和服务安全请您妥善保管。 image.png

另外，我们为您提供了教学视频，您可以直接浏览视频获取详细教程。

2. 获取 Access_token
百度AI开放平台使用OAuth2.0授权调用开放API，调用API时必须在URL中带上Access_token参数，Access token默认有效期为30天，获取Access_token的流程如下：

请求URL数据格式
向授权服务地址https://aip.baidubce.com/oauth/2.0/token发送请求（推荐使用POST），并在URL中带上以下参数：

grant_type： 必须参数，固定为client_credentials；
client_id： 必须参数，应用的API Key；
client_secret： 必须参数，应用的Secret Key；
例如：

https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials&client_id=Va5yQRHlA4Fq5eR3LT0vuXV4&client_secret=0rDSjzQ20XUj5itV6WRtznPQSzr5pVw2&



百度密钥之类的

baidu:
  ai:
    app-id: 122045146
    api-key: 2M3uJOYG4MYVwvw6pU9EQTHI
    secret-key: 8qxgrmbMC4Gpn1OMGPqaQKKQ2sHKWmHn




# 图像去雾
请求示例

HTTP 方法：POST

请求URL： https://aip.baidubce.com/rest/2.0/image-process/v1/dehaze

URL参数：

参数	值
access_token	通过API Key和Secret Key获取的access_token，参考”Access Token获取”
Header如下：

参数	值
Content-Type	application/x-www-form-urlencoded
Body中放置请求参数，参数详情如下：

请求参数

参数	是否必选	类型	可选值范围	说明
image	和url二选一	string	-	base64编码后大小不超过10M(参考：原图大约为8M以内），最短边至少10px，最长边最大5000px，长宽比4：1以内。注意：图片的base64编码是不包含图片头的，如（data:image/jpg;base64,）
url	和image二选一	string	-	图片完整URL，URL长度不超过1024字节，URL对应的图片base64编码后大小不超过10M(参考：原图大约为8M以内），最短边至少10px，最长边最大5000px，长宽比4：1以内，支持jpg/png/bmp格式，当image字段存在时url字段失效。
请求代码示例

提示一：使用示例代码前，请记得替换其中的示例Token、图片地址或Base64信息。

提示二：部分语言依赖的类或库，请在代码注释中查看下载地址。

 'https://aip.baidubce.com/rest/2.0/image-process/v1/dehaze?access_token=【调用鉴权接口获取的token】' --data 'image=【图片Base64编码，需UrlEncode】' -H 'Content-Type:application/x-www-form-urlencoded'
返回说明
返回参数

字段	是否必选	类型	说明
log_id	是	uint64	唯一的log id，用于问题定位
image	否	string	base64编码图片
返回示例

{
     "log_id":739539874,
     "image":"处理后图片的Base64编码"
}



# 图像对比度增强

请求说明
请求示例

HTTP 方法：POST

请求URL： https://aip.baidubce.com/rest/2.0/image-process/v1/contrast_enhance

URL参数：

参数	值
access_token	通过API Key和Secret Key获取的access_token，参考”Access Token获取”
Header如下：

参数	值
Content-Type	application/x-www-form-urlencoded
Body中放置请求参数，参数详情如下：

请求参数

参数	是否必选	类型	可选值范围	说明
image	和url二选一	string	-	base64编码后大小不超过10M(参考：原图大约为8M以内），最短边至少10px，最长边最大5000px，长宽比4：1以内。注意：图片的base64编码是不包含图片头的，如（data:image/jpg;base64,）
url	和image二选一	string	-	图片完整URL，URL长度不超过1024字节，URL对应的图片base64编码后大小不超过10M(参考：原图大约为8M以内），最短边至少10px，最长边最大5000px，长宽比4：1以内，支持jpg/png/bmp格式，当image字段存在时url字段失效。
请求代码示例

提示一：使用示例代码前，请记得替换其中的示例Token、图片地址或Base64信息。

提示二：部分语言依赖的类或库，请在代码注释中查看下载地址。

bash
PHP
Java
Python
CPP
C#
curl -i -k 'https://aip.baidubce.com/rest/2.0/image-process/v1/contrast_enhance?access_token=【调用鉴权接口获取的token】' --data 'image=【图片Base64编码，需UrlEncode】' -H 'Content-Type:application/x-www-form-urlencoded'
返回说明
返回参数

字段	是否必选	类型	说明
log_id	是	uint64	唯一的log id，用于问题定位
image	否	string	base64编码图片
返回示例

{
    "log_id":739539874,
    "image":"处理后图片的Base64编码"
}


# 人物动漫化


在线调试
您可以在 示例代码中心 中调试该接口，可进行签名验证、查看在线调用的请求内容和返回结果、示例代码的自动生成。

请求说明
请求示例

HTTP 方法：POST

请求URL： https://aip.baidubce.com/rest/2.0/image-process/v1/selfie_anime

URL参数：

参数	值
access_token	通过API Key和Secret Key获取的access_token，参考”Access Token获取”
Header如下：

参数	值
Content-Type	application/x-www-form-urlencoded
Body中放置请求参数，参数详情如下：

请求参数

参数	是否必选	类型	可选值范围	说明
image	和url二选一	string	-	base64编码后大小不超过10M(参考：原图大约为8M以内），最短边至少10px，最长边最大5000px，长宽比4：1以内。注意：图片的base64编码是不包含图片头的，如（data:image/jpg;base64,）
url	和image二选一	string	-	图片完整URL，URL长度不超过1024字节，URL对应的图片base64编码后大小不超过10M(参考：原图大约为8M以内），最短边至少10px，最长边最大5000px，长宽比4：1以内,支持jpg/png/bmp格式，当image字段存在时url字段失效。
type	false	string	-	anime或者anime_mask。前者生成二次元动漫图，后者生成戴口罩的二次元动漫人像
mask_id	false	int	1-8	在type参数填入anime_mask时生效，1～8之间的整数，用于指定所使用的口罩的编码。type参数没有填入anime_mask，或mask_id 为空时，生成不戴口罩的二次元动漫图。
请求代码示例

提示一：使用示例代码前，请记得替换其中的示例Token、图片地址或Base64信息。

提示二：部分语言依赖的类或库，请在代码注释中查看下载地址。

bash
PHP
Java
Python
CPP
C#
人像动漫化
curl -i -k 'https://aip.baidubce.com/rest/2.0/image-process/v1/selfie_anime?access_token=【调用鉴权接口获取的token】' --data 'image=【图片Base64编码，需UrlEncode】' -H 'Content-Type:application/x-www-form-urlencoded'
返回说明
返回参数

字段	是否必选	类型	说明
log_id	是	uint64	唯一的log id，用于问题定位
image	否	string	处理后图片的Base64编码
返回示例

{
     "log_id":739539874,
     "image":base64str
}