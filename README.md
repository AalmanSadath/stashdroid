# Stashdroid

A whole-phone backup for Android that you store where you choose: a folder on the phone, an SD
card, a cloud app such as Google Drive, OneDrive or Nextcloud, or your own server. No root needed.

## Status

Early development. This version backs up one folder of your phone's shared storage (for example
`DCIM`, where your camera keeps photos and videos) to a folder you pick, restores it into an
empty folder, and checks that every restored file matches the original.

Planned for the first release: all of shared storage, contacts, SMS, call log and the list of
installed apps, on a schedule, with restore item by item on a new phone. Self-hosted storage over
WebDAV, SFTP, S3 and SMB, paired by scanning a QR code.

## How backups are stored

Files are split into chunks, and each chunk is stored once. A file that did not change since the
last backup is not copied again, and a copy of a file costs no extra space. Each backup run is
kept as its own version in the `stashdroid` folder inside the folder you pick.

Backups are not encrypted yet. Pick a folder you trust.

## Requirements

Android 11 or later. Stashdroid asks for all-files access so it can read your photos, videos,
downloads and documents. It cannot read other apps' private data; Android keeps that from every
app without root.

## License

GPL-3.0. See [LICENSE](LICENSE).
