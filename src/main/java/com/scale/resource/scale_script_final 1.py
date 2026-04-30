import serial
import serial.tools.list_ports
import time
import logging
import os
import sys
import asyncio
import websockets
import threading
import json

# ============================
# Global Variables
# ============================
latest_weight = None
serial_port_name = None  # Will be automatically discovered.
connected_clients = set()
websocket_loop = None
stop_event = threading.Event()  # Signals threads to stop

# ============================
# WebSocket Code
# ============================
async def websocket_handler(websocket, path):
    connected_clients.add(websocket)
    logging.info(f"WebSocket: Client connected: {websocket.remote_address}")
    try:
        async for message in websocket:
            # This server does not expect to receive messages.
            logging.debug(f"WebSocket: Received message from client: {message}")
    except websockets.exceptions.ConnectionClosed as e:
        logging.info(f"WebSocket: Connection closed: {e}")
    finally:
        connected_clients.remove(websocket)
        logging.info(f"WebSocket: Client disconnected: {websocket.remote_address}")

async def broadcast_data(data):
    if connected_clients:
        message = json.dumps(data)
        logging.debug(f"WebSocket: Broadcasting message: {message}")
        await asyncio.gather(*[client.send(message) for client in connected_clients])
    else:
        logging.debug("WebSocket: No connected clients to broadcast to.")

async def periodic_broadcast():
    """
    Broadcasts the latest weight every 0.1 seconds.
    """
    global latest_weight
    while True:
        if latest_weight is not None:
            data = {'port': serial_port_name, 'weight': latest_weight}
            logging.debug(f"Periodic Broadcast: Latest weight: {latest_weight}")
            await broadcast_data(data)
        await asyncio.sleep(0.1)

def start_websocket_server():
    global websocket_loop
    websocket_loop = asyncio.new_event_loop()
    asyncio.set_event_loop(websocket_loop)
    start_server = websockets.serve(websocket_handler, "localhost", 6789)
    logging.info("WebSocket: Starting server on ws://localhost:6789")
    websocket_loop.run_until_complete(start_server)
    websocket_loop.create_task(periodic_broadcast())
    logging.info("WebSocket: Periodic broadcast task started")
    websocket_loop.run_forever()

# ============================
# Packet Splitting and Parsing Helpers
# ============================
def extract_complete_packets(buffer):
    """
    Extract complete packets from a buffer.
    A packet starts with STX (0x02) and ends with one of the terminators:
    CR (0x0D), LF (0x0A), or EOT (0x04).
    Returns a tuple of (list_of_complete_packets, remaining_buffer).
    """
    packets = []
    remaining = buffer

    while True:
        stx_index = remaining.find(b'\x02')
        if stx_index == -1:
            # No start marker found; return any accumulated packets and reset buffer.
            return (packets, b'')
        # Look for possible terminators after the STX.
        cr_index = remaining.find(b'\x0D', stx_index)
        lf_index = remaining.find(b'\n', stx_index)
        eot_index = remaining.find(b'\x04', stx_index)
        terminator_indexes = [i for i in (cr_index, lf_index, eot_index) if i != -1]
        if not terminator_indexes:
            # No complete packet yet; return what's not yet processed.
            return (packets, remaining[stx_index:])
        end_index = min(terminator_indexes)
        # Include the terminator in the packet.
        packet = remaining[stx_index:end_index + 1]
        packets.append(packet)
        # Remove the processed packet from the buffer.
        remaining = remaining[end_index + 1:]
        if not remaining:
            break

    return (packets, remaining)

def extract_weight_from_packet(packet):
    """
    Extract the weight from a packet.
    Assumes the packet starts with STX (0x02) and contains an ETX (0x03).
    It extracts the payload (between STX and ETX), splits it by whitespace,
    and uses the second token as the weight.
    
    Example packet: b'\x02!10 000140 000000\x03\x07'
    """
    logging.debug(f"Extracting weight from packet: {packet!r}")
    try:
        if packet.startswith(b'\x02'):
            etx_index = packet.find(b'\x03')
            if etx_index != -1:
                payload_bytes = packet[1:etx_index]
                payload = payload_bytes.decode('ascii', errors='ignore')
                logging.debug(f"Decoded payload: {payload}")
                tokens = payload.split()  # Expected: ["!10", "000140", "000000"]
                if len(tokens) >= 2:
                    weight_str = tokens[1]
                    weight = int(weight_str)  # Converts string to int, automatically removing zero-padding.
                    logging.info(f"Extracted weight: {weight} (from token '{weight_str}')")
                    return weight
                else:
                    logging.error(f"Payload does not have enough tokens: {payload}")
            else:
                logging.error(f"ETX (0x03) not found in packet: {packet!r}")
        else:
            logging.error(f"Packet does not start with STX (0x02): {packet!r}")
    except Exception as e:
        logging.error(f"Error extracting weight: {e}", exc_info=True)
    return None

# ============================
# Serial Port Reading Function (Non-blocking)
# ============================
def read_from_port(port_name, baudrate=9600, timeout=0.1):
    """
    Continuously read from the serial port in a non-blocking manner.
    Incoming data is accumulated into a buffer, and complete packets are
    extracted and processed immediately.
    """
    global latest_weight
    try:
        logging.info(f"Serial: Attempting to open port {port_name}...")
        ser = serial.Serial(
            port=port_name,
            baudrate=baudrate,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=timeout
        )
        logging.info(f"Serial: Successfully opened port {port_name}")
        logging.info(f"Serial: Listening on {port_name}... (Press Ctrl+C to exit)")

        buffer = b''  # Buffer to accumulate incoming bytes

        while not stop_event.is_set():
            if ser.in_waiting > 0:  # ser.in_waiting returns the number of bytes in the input buffer.
                data = ser.read(ser.in_waiting)
                buffer += data
                logging.debug(f"Serial: Received data chunk: {data!r}")
                packets, buffer = extract_complete_packets(buffer)
                if packets:
                    logging.debug(f"Serial: Extracted {len(packets)} complete packet(s).")
                for packet in packets:
                    weight = extract_weight_from_packet(packet)
                    if weight is not None:
                        latest_weight = weight
                        logging.info(f"Serial: Updated latest weight: {weight}")
                    else:
                        logging.debug(f"Serial: No valid weight extracted from packet: {packet!r}")
            else:
                time.sleep(0.01)
    except serial.SerialException as e:
        logging.error(f"Serial: Could not open port {port_name}: {e}", exc_info=True)
    except Exception as e:
        logging.error(f"Serial: Unexpected error on {port_name}: {e}", exc_info=True)
    finally:
        if 'ser' in locals() and ser.is_open:
            ser.close()
            logging.info(f"Serial: Port {port_name} closed.")

# ============================
# New: Port Scanning and Validation
# ============================
def find_scale_port():
    """
    Scan available serial ports and test if they are our scale port.
    This function opens each port briefly and tests for expected data.
    It returns the device name (e.g., "COM3") of a matching port, or None if not found.
    """
    ports = serial.tools.list_ports.comports()
    logging.info("Scanning available serial ports for the scale device...")
    for port in ports:
        logging.info(f"Found port: {port.device} - {port.description}")
        try:
            ser = serial.Serial(port.device, baudrate=9600, timeout=1)
            # Allow the device time to initialize.
            time.sleep(2)
            data = ser.read(200)  # Read a larger chunk of data.
            ser.close()
            logging.debug(f"Data read from port {port.device}: {data!r}")
            # Look for the expected marker (adjust as necessary).
            if b'\x02!10' in data or b'!10' in data:
                logging.info(f"Scale device appears to be on port: {port.device}")
                return port.device
            else:
                logging.info(f"Port {port.device} did not return expected scale data.")
        except Exception as e:
            logging.error(f"Error testing port {port.device}: {e}", exc_info=True)
    return None

# ============================
# Main Execution
# ============================
def main():
    global serial_port_name
    # Determine the directory of the script/executable for log file placement.
    if getattr(sys, 'frozen', False):
        script_dir = os.path.dirname(sys.executable)
    else:
        script_dir = os.path.dirname(os.path.abspath(__file__))
    log_file_path = os.path.join(script_dir, 'serial_log.txt')
    
    # Configure logging: output to both file and console.
    logging.basicConfig(
        level=logging.DEBUG,  # Log DEBUG and higher levels.
        format='%(asctime)s [%(levelname)s] %(message)s',
        handlers=[
            logging.FileHandler(log_file_path, mode='a'),
            logging.StreamHandler()
        ]
    )
    
    # Continuously scan for the scale device until it is found.
    while serial_port_name is None:
        serial_port_name = find_scale_port()
        if serial_port_name is None:
            logging.error("Main: Could not find a valid serial port for the scale device. Retrying in 5 seconds...")
            time.sleep(5)
    
    try:
        # Start the WebSocket server in a separate daemon thread.
        websocket_thread = threading.Thread(target=start_websocket_server, daemon=True)
        websocket_thread.start()
        logging.info("Main: WebSocket server started on ws://localhost:6789")
        
        # Start the serial port reading thread.
        serial_thread = threading.Thread(target=read_from_port, args=(serial_port_name,), daemon=True)
        serial_thread.start()
        
        # Main loop to keep the program running.
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        logging.info("Main: Program terminated by user (KeyboardInterrupt).")
        stop_event.set()  # Signal threads to stop.
    except Exception as e:
        logging.error(f"Main: Unexpected error: {e}", exc_info=True)
        if sys.stdin.isatty():
            input("An error occurred. Press Enter to exit...")
    finally:
        stop_event.set()
        logging.info("Main: Waiting for serial thread to finish...")
        serial_thread.join()
        if websocket_loop and websocket_loop.is_running():
            # Close any remaining WebSocket clients.
            for client in list(connected_clients):
                asyncio.run_coroutine_threadsafe(client.close(), websocket_loop)
            websocket_loop.call_soon_threadsafe(websocket_loop.stop)
        logging.info("Main: WebSocket server stopped.")
        websocket_thread.join()

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        logging.error(f"Top-level error: {e}", exc_info=True)
        if sys.stdin.isatty():
            input("A fatal error occurred. Press Enter to exit...")
