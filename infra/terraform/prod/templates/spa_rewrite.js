// CloudFront Function(viewer-request) — 프론트 기본 동작에만 붙는다.
//
// /board/12 같은 화면 주소로 새로고침하면 S3에는 그런 파일이 없어 403이 난다. 확장자가 없는 경로는
// 화면 주소로 보고 index.html을 돌려준다(React Router가 주소를 읽어 화면을 그린다).
//
// 배포 전체에 거는 "사용자 정의 오류 응답"으로 하지 않는 이유: 그건 /api의 403/404까지 index.html(200)로
// 바꿔 버려 프론트가 오류를 알아채지 못한다.
function handler(event) {
  var request = event.request;
  var uri = request.uri;
  var lastSegment = uri.substring(uri.lastIndexOf('/') + 1);
  if (lastSegment.indexOf('.') === -1) {
    request.uri = '/index.html';
  }
  return request;
}
