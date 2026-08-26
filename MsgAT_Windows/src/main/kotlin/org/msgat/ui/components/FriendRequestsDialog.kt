package org.msgat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.msgat.data.db.FriendRequest

@Composable
fun FriendRequestsDialog(
    requests: List<FriendRequest>,
    onDismiss: () -> Unit,
    onAccept: (String, String, String) -> Unit,
    onDecline: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .heightIn(max = 500.dp)
                .background(color = Color(0xFF1E1E1E), shape = RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Text(
                text = "Friend Requests",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (requests.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No pending requests.", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(requests) { req ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .background(Color(0xFF2C2C2C), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp).background(Color.Gray, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(req.nickname.take(1).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(req.nickname, color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                Text("UUID: ${req.senderUuid.take(12)}...", color = Color.Gray, fontSize = 12.sp)
                            }

                            IconButton(onClick = { onAccept(req.senderUuid, req.nickname, req.avatarPath) }) {
                                Icon(Icons.Default.Check, contentDescription = "Accept", tint = Color(0xFF00FF00))
                            }
                            IconButton(onClick = { onDecline(req.senderUuid) }) {
                                Icon(Icons.Default.Clear, contentDescription = "Decline", tint = Color.Red)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close", color = Color.Gray) }
            }
        }
    }
}
