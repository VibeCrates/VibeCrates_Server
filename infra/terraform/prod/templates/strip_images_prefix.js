// CloudFront Function(viewer-request) — /images/* 동작에만 붙는다.
//
// 브라우저는 https://<도메인>/images/book/123.jpg로 받는데(storage.bucket-url = ${SITE_URL}/images),
// 버킷의 객체 키는 로컬 MinIO와 같은 book/123.jpg다. 앞의 /images를 떼어 버킷 키와 맞춘다.
// 버킷 쪽 키를 images/로 바꾸지 않는 이유는 로컬과 운영의 객체 키를 같게 두려는 것이다.
function handler(event) {
  var request = event.request;
  request.uri = request.uri.replace(/^\/images/, '');
  return request;
}
