# Word Popup Overlay

Android app for periodic vocabulary popups over other apps.

## Data folder

In the app, tap **Chọn thư mục chứa file .txt** and select the folder that contains your vocabulary files. Android can grant access to the whole selected directory; the app then reads all `.txt` files in it, including nested folders.

Expected format:

1.
two-story
hai tầng
Example: They live in a nice two-story house.
Dịch: Họ sống trong một ngôi nhà hai tầng đẹp.

2.
right-hand
bên phải
Example: Turn onto the right-hand street.
Dịch: Hãy rẽ vào con đường bên phải.

3.
second-largest
lớn thứ hai
Example: This is the second-largest city in the country.
Dịch: Đây là thành phố lớn thứ hai ở đất nước này.

The parser uses the numbered line (`1.`, `2.`, etc.) as the beginning of each entry. It reads the next two non-empty lines as word and meaning, and an `Example:` line as the English example.

## Popup behavior

- Popup stays open until **×** is pressed.
- **VD** toggles the example sentence.
- If a previous popup is still open, the next scheduled popup is skipped rather than stacked on top of it.

## GitHub Actions

The file `GITHUB_WORKFLOW_CONTENT.txt` contains the workflow to paste into `.github/workflows/build.yml` in GitHub. Then run **Actions → Build Android APK → Run workflow**.

## Files to customize

- `MainActivity.kt`: folder picker, interval and main screen behavior.
- `PopupService.kt`: reading/parsing `.txt` files and popup behavior.
- `res_layout_popup_word.xml`: popup appearance/buttons.
- `res_layout_activity_main.xml`: app screen.

Normally you do **not** need to edit code to change vocabulary. Just select the folder containing your `.txt` files from inside the app.
