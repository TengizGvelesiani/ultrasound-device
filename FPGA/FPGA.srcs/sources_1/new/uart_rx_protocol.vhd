library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
use IEEE.NUMERIC_STD.ALL;

-- ============================================================================
-- Module Name: uart_rx_protocol
-- Description: 2 Mbaud UART Receiver & 16-Byte Binary Command Packet Parser.
--              Samples incoming bits at the exact midpoint (cycle 25/50) with
--              start-bit glitch rejection, synchronizes to 0xA5 0x5A headers,
--              computes the XOR payload checksum, and strobes valid commands.
-- ============================================================================
entity uart_rx_protocol is
    Port (
        clk100mhz    : in  STD_LOGIC;
        uart_rx      : in  STD_LOGIC;

        -- Decoded Command Strobes & Parameters
        cmd_strobe   : out STD_LOGIC;
        cmd_id       : out STD_LOGIC_VECTOR(7 downto 0);
        cmd_axis     : out STD_LOGIC_VECTOR(7 downto 0);
        cmd_dir      : out STD_LOGIC_VECTOR(7 downto 0);
        param1       : out unsigned(31 downto 0);
        param2       : out unsigned(31 downto 0);
        param3       : out unsigned(15 downto 0)
    );
end uart_rx_protocol;

architecture Behavioral of uart_rx_protocol is

    -- Metastability Synchronizers
    signal rx_sync1      : STD_LOGIC := '1';
    signal rx_sync2      : STD_LOGIC := '1';

    -- UART RX Deserializer (2,000,000 baud @ 100 MHz = 50 clock cycles/bit)
    type rx_state_type is (RX_IDLE, RX_START, RX_DATA, RX_STOP);
    signal rx_state      : rx_state_type := RX_IDLE;
    signal rx_baud_cnt   : integer range 0 to 63 := 0;
    signal rx_bit_idx    : integer range 0 to 7 := 0;
    signal rx_shift_reg  : STD_LOGIC_VECTOR(7 downto 0) := (others => '0');
    signal rx_byte_valid : STD_LOGIC := '0';
    signal rx_byte_data  : STD_LOGIC_VECTOR(7 downto 0) := (others => '0');

    -- 16-byte Packet Receiver & Checksum Verification
    type pkt_state_type is (PKT_SYNC1, PKT_SYNC2, PKT_DATA, PKT_CHECK);
    signal pkt_state     : pkt_state_type := PKT_SYNC1;
    type packet_array is array (0 to 15) of STD_LOGIC_VECTOR(7 downto 0);
    signal pkt_buffer    : packet_array := (others => (others => '0'));
    signal pkt_byte_idx  : integer range 0 to 15 := 0;
    signal pkt_checksum  : STD_LOGIC_VECTOR(7 downto 0) := (others => '0');

    -- Output Registers
    signal cmd_strobe_r  : STD_LOGIC := '0';
    signal cmd_id_r      : STD_LOGIC_VECTOR(7 downto 0) := (others => '0');
    signal cmd_axis_r    : STD_LOGIC_VECTOR(7 downto 0) := (others => '0');
    signal cmd_dir_r     : STD_LOGIC_VECTOR(7 downto 0) := (others => '0');
    signal param1_r      : unsigned(31 downto 0) := (others => '0');
    signal param2_r      : unsigned(31 downto 0) := (others => '0');
    signal param3_r      : unsigned(15 downto 0) := (others => '0');

begin

    cmd_strobe <= cmd_strobe_r;
    cmd_id     <= cmd_id_r;
    cmd_axis   <= cmd_axis_r;
    cmd_dir    <= cmd_dir_r;
    param1     <= param1_r;
    param2     <= param2_r;
    param3     <= param3_r;

    -- UART RX Deserializer process (2,000,000 baud @ 100 MHz, 50 cycles per bit)
    process(clk100mhz)
    begin
        if rising_edge(clk100mhz) then
            rx_sync1      <= uart_rx;
            rx_sync2      <= rx_sync1;
            rx_byte_valid <= '0';

            case rx_state is
                when RX_IDLE =>
                    rx_baud_cnt <= 0;
                    rx_bit_idx  <= 0;
                    if rx_sync2 = '0' then -- Falling edge of start bit detected
                        rx_state <= RX_START;
                    end if;

                when RX_START =>
                    if rx_baud_cnt = 24 then -- Sample midpoint of start bit (cycle 25)
                        if rx_sync2 = '0' then
                            rx_baud_cnt <= 0;
                            rx_bit_idx  <= 0;
                            rx_state    <= RX_DATA;
                        else
                            rx_state <= RX_IDLE; -- Glitch rejected
                        end if;
                    else
                        rx_baud_cnt <= rx_baud_cnt + 1;
                    end if;

                when RX_DATA =>
                    if rx_baud_cnt = 49 then
                        rx_baud_cnt  <= 0;
                        rx_shift_reg <= rx_sync2 & rx_shift_reg(7 downto 1); -- LSB first
                        if rx_bit_idx = 7 then
                            rx_state <= RX_STOP;
                        else
                            rx_bit_idx <= rx_bit_idx + 1;
                        end if;
                    else
                        rx_baud_cnt <= rx_baud_cnt + 1;
                    end if;

                when RX_STOP =>
                    if rx_baud_cnt = 49 then
                        rx_baud_cnt <= 0;
                        if rx_sync2 = '1' then -- Valid stop bit verified
                            rx_byte_data  <= rx_shift_reg;
                            rx_byte_valid <= '1';
                        end if;
                        rx_state <= RX_IDLE;
                    else
                        rx_baud_cnt <= rx_baud_cnt + 1;
                    end if;
            end case;
        end if;
    end process;

    -- 16-Byte Packet Assembler & Checksum Verifier
    process(clk100mhz)
    begin
        if rising_edge(clk100mhz) then
            cmd_strobe_r <= '0';

            if rx_byte_valid = '1' then
                case pkt_state is
                    when PKT_SYNC1 =>
                        if rx_byte_data = x"A5" then
                            pkt_buffer(0) <= rx_byte_data;
                            pkt_state     <= PKT_SYNC2;
                        end if;

                    when PKT_SYNC2 =>
                        if rx_byte_data = x"5A" then
                            pkt_buffer(1) <= rx_byte_data;
                            pkt_byte_idx  <= 2;
                            pkt_checksum  <= (others => '0');
                            pkt_state     <= PKT_DATA;
                        elsif rx_byte_data = x"A5" then
                            -- Repeated 0xA5 received: keep listening for following 0x5A
                            pkt_buffer(0) <= rx_byte_data;
                            pkt_state     <= PKT_SYNC2;
                        else
                            pkt_state <= PKT_SYNC1;
                        end if;

                    when PKT_DATA =>
                        pkt_buffer(pkt_byte_idx) <= rx_byte_data;
                        if pkt_byte_idx <= 14 then
                            pkt_checksum <= pkt_checksum xor rx_byte_data;
                        end if;

                        if pkt_byte_idx = 14 then
                            pkt_state <= PKT_CHECK;
                        else
                            pkt_byte_idx <= pkt_byte_idx + 1;
                        end if;

                    when PKT_CHECK =>
                        pkt_buffer(15) <= rx_byte_data;
                        if rx_byte_data = pkt_checksum then
                            -- Checksum verified: latch parameters into outputs
                            cmd_id_r   <= pkt_buffer(2);
                            cmd_axis_r <= pkt_buffer(3);
                            cmd_dir_r  <= pkt_buffer(4);
                            param1_r   <= unsigned(pkt_buffer(5)) & unsigned(pkt_buffer(6)) & unsigned(pkt_buffer(7)) & unsigned(pkt_buffer(8));
                            param2_r   <= unsigned(pkt_buffer(9)) & unsigned(pkt_buffer(10)) & unsigned(pkt_buffer(11)) & unsigned(pkt_buffer(12));
                            param3_r   <= unsigned(pkt_buffer(13)) & unsigned(pkt_buffer(14));
                            cmd_strobe_r <= '1';
                        end if;
                        pkt_state <= PKT_SYNC1;
                end case;
            end if;
        end if;
    end process;

end Behavioral;
