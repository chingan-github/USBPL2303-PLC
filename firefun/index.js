
  /**
     * Copyright 2016 Google Inc. All Rights Reserved.
     *
     * Licensed under the Apache License, Version 2.0 (the "License");
     * you may not use this file except in compliance with the License.
     * You may obtain a copy of the License at
     *
     *      http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS,
     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     * See the License for the specific language governing permissions and
     * limitations under the License.
     */
    'use strict';

    const functions = require('firebase-functions');
    const admin = require('firebase-admin');
    const nodemailer = require('nodemailer');
    const gmailEmail = encodeURIComponent(functions.config().gmail.email);
    const gmailPassword = encodeURIComponent(functions.config().gmail.password);
    const mailTransport = nodemailer.createTransport(
    `smtps://${gmailEmail}:${gmailPassword}@smtp.gmail.com`);

    admin.initializeApp(functions.config().firebase);


    // Removes siblings of the node that element that triggered the function if there are more than MAX_LOG_COUNT.
    // In this example we'll keep the max number of chat message history to MAX_LOG_COUNT.
    exports.logLimit= functions.database.ref('/LOG/{service}/{deviceId}/{message}').onWrite(event => {
      // Max number of lines of the chat history.
      const MAX_LOG_COUNT = 1024-1;
      const parentRef = event.data.ref;
      return parentRef.once('value').then(snapshot => {
        if (snapshot.numChildren() > MAX_LOG_COUNT) {
          let childCount = 0;
          const updates = {};
          snapshot.forEach(function(child) {
            if (++childCount < snapshot.numChildren() - MAX_LOG_COUNT) {
              updates[child.key] = null;
            }
          });
          // Update the parent. This effectively removes the extra children.
          return parentRef.update(updates);
        }
      });
    });


exports.IIDPUSH= functions.database.ref('/LOG/PUSHIID/').onWrite(event => {
      const parentRef = event.data.ref;
      return parentRef.once('value').then(snapshot => {
        //todo
      if (snapshot.memberEmail!=null){
        IID_Push(snapshot.memberEmail,snapshot.message);
      }
    });
  });

exports.topicsPUSH= functions.database.ref('/LOG/PUSHTopics/').onWrite(event => {
      const parentRef = event.data.ref;
      return parentRef.once('value').then(snapshot => {
        //todo
        if (snapshot.deviceId!=null){
        topics_Push(snapshot.deviceId,snapshot.message);
      }
    });
  });

/**
 * Get the Device Tokens for the given user.
 *
 */
function getDeviceTokens(memberEmail) {

  return admin.database().ref(`/USER/${memberEmail.replace(".","_")}/token`).once('value').then(snapshot=> {
    if (snapshot.exists()) {
      return snapshot.val();
    }
    return [];
   });
 }


/**
 * Sends a coupon code via FCM to the given user.
 */
function IID_Push(memberEmail,message) {
  // Fetching all the user's device tokens.
  return getDeviceTokens(memberEmail).then(token => {
    if (token.length > 0) {
      // Notification details.
      let payload = {
        notification: {
          title: message.title,
          body: message.body
        }
      };

      return admin.messaging().sendToDevice(token, payload);
    }
  });
}

function topics_Push(topicsId,message) {
  // Notification details.
  let payload = {
    notification: {
      title: message.title,
      body: message.body
      }
  };
   return admin.messaging().sendToTopic(topicId, payload);
}

exports.emailPUSH = functions.database.ref('/LOG/EMAIL/').onWrite(event => {
  const parentRef = event.data.ref;
  return parentRef.once('value').then(snapshot => {
    const email =snapshot.memberEmail; // The email of the user.
    const message =snapshot.message ;
    return sendEmail(email,message);
  });
});

// Sends a welcome email to the given user.
function sendEmail(email, message) {
  const mailOptions = {
    from: '"E2GO"<noreply@firebase.com>',
    to: email
  };
  // The user unsubscribed to the newsletter.
  mailOptions.subject = `E2GO通知`;
  mailOptions.text = message;
  return mailTransport.sendMail(mailOptions).then(() => {
    console.log('New welcome email sent to:', email);
  });
}
