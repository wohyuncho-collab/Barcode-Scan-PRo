LabelScan PRO — ML Kit + ZXing 동시 분석 Android APK 프로젝트

구성
- index.html: 기존 LabelScan PRO HTML 앱 (기존 결과 처리 콜백 n(barcode)을 유지하도록 연결)
- src/dual-scanner-bridge.js: HTML ↔ Capacitor 네이티브 스캐너 브리지
- native/DualScannerPlugin.java: Capacitor 플러그인 및 바코드 이벤트 전달
- native/DualScannerActivity.java: CameraX 카메라 프리뷰, ML Kit + ZXing 동시 분석
- .github/workflows/build.yml: GitHub Actions APK 빌드

GitHub 사용 방법
1. ZIP을 풀고 프로젝트 안의 파일을 저장소 루트에 업로드합니다.
2. 앱 HTML은 저장소 루트의 index.html로 둡니다.
3. .github/workflows/build.yml이 기본 브랜치(main 또는 master)에 포함되도록 커밋합니다.
4. GitHub 저장소의 Actions → Build LabelScan PRO Dual Scanner APK → Run workflow를 실행합니다.
5. 성공한 실행의 Artifacts에서 LabelScan-PRO-DualScanner-APK를 다운로드합니다.

스캐너 동작
- CameraX가 하나의 카메라 프레임을 제공하고 ML Kit와 ZXing이 각각 분석합니다.
- ML Kit: Android 번들형 com.google.mlkit:barcode-scanning:17.3.0
- ZXing: com.google.zxing:core:3.5.3
- 두 엔진은 APK 의존성으로 포함되므로 스캐너 엔진을 첫 실행 때 인터넷에서 내려받지 않습니다.
- ZXing은 라이브러리가 지원하는 모든 BarcodeFormat을 시도하며, ML Kit는 FORMAT_ALL_FORMATS를 사용합니다.
- 중복 결과는 네이티브에서 제한하고, 검출된 문자열은 기존 HTML의 n(barcode) 결과 처리 함수로 전달됩니다.
- Android APK에서는 네이티브 카메라 화면을 사용합니다. 웹 브라우저에서는 기존 HTML 카메라/BarcodeDetector 경로를 유지합니다.

주의
- 이 작업 환경에서는 GitHub Actions의 실제 Android 빌드를 실행하거나 실기기 카메라 테스트를 완료한 것이 아닙니다. 첫 Actions 빌드 후 컴파일 로그와 Android 실기기에서 권한, 초점, 방향, 모든 바코드 유형, 닫기 동작을 확인하세요.
- 카메라 권한은 실행 시 요청됩니다. 오프라인 보장은 두 스캐너 엔진의 모델/라이브러리를 APK에 포함한다는 의미이며, 앱의 다른 온라인 기능에는 적용되지 않습니다.


[앱 아이콘]
- assets/app-icon/LabelScan_PRO_icon_master.png: 원본 아이콘
- android-res/: Android 해상도별 런처 아이콘 및 Adaptive Icon 리소스
- GitHub Actions가 Capacitor Android 프로젝트 생성 후 리소스를 복사하고 AndroidManifest.xml의 icon/roundIcon/label을 설정합니다.
- 앱 표시 이름: LabelScan PRO
