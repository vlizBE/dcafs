#!/bin/bash
SERVICE_NAME='dcafs.service'
SCRIPT="$(readlink --canonicalize-existing "$0")"
SCRIPT_PATH="$(dirname "$SCRIPT")"
JAR_FILE=$(echo ` ls | grep dcafs*.jar`)

SERVICE_PATH="/lib/systemd/system"
SERVICE_FILE=$SERVICE_PATH/$SERVICE_NAME

sudo cat > $SERVICE_FILE << EOF
[Unit]
Description=Dcafs Data Acquisition System Service
After=multi-user.target
[Service]
Type=simple
ExecStart=java -jar $SCRIPT_PATH/$JAR_FILE
Restart=on-failure
RestartSec=3s
[Install]
WantedBy=multi-user.target
EOF

chmod 644 $SERVICE_FILE

systemctl daemon-reload
systemctl enable $SERVICE_NAME
systemctl start $SERVICE_NAME
systemctl status $SERVICE_NAME
