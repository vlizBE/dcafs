sudo systemctl stop dcafs.service
sudo systemctl disable dcafs.service
sudo  rm -f /lib/systemd/system/dcafs.service
sudo systemctl daemon-reload
sudo systemctl reset-failed

