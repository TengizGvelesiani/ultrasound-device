library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
use IEEE.NUMERIC_STD.ALL;

-- ============================================================================
-- Module Name: acquisition_ram
-- Description: Ultrasound A-Scan Line Buffer & High-Speed 2 Mbaud UART Transmitter.
--              Stores 1024 decimated acoustic envelope samples per transmission burst,
--              then streams a 4-byte frame synchronization header (0xFF 0xAA 0x55 0x00)
--              followed by the 1024 envelope bytes at 2.000.000 baud to the PC.
-- ============================================================================
entity acquisition_ram is
    Port (
        clk100mhz     : in  STD_LOGIC;
        pulse_trigger : in  STD_LOGIC; -- Trigger from pulser_fsm
        sample_data   : in  STD_LOGIC_VECTOR(7 downto 0);
        sample_valid  : in  STD_LOGIC; -- From dsp_pipeline

        -- Serial Streamer Output
        uart_tx       : out STD_LOGIC;
        busy          : out STD_LOGIC
    );
end acquisition_ram;

architecture Behavioral of acquisition_ram is

    constant BAUD_RATE_TICKS : integer := 50; -- 100 MHz / 2,000,000 baud = 50 clock cycles/bit

    type ram_type is array (0 to 1023) of STD_LOGIC_VECTOR(7 downto 0);
    signal adc_ram        : ram_type := (others => (others => '0'));
    signal ram_write_idx  : integer range 0 to 1023 := 0;
    signal ram_read_idx   : integer range 0 to 1023 := 0;

    type logger_state_type is (
        WAIT_PULSE,
        CAPTURE_SAMPLES,
        UART_SEND_HEADER_1,
        UART_SEND_HEADER_2,
        UART_SEND_HEADER_3,
        UART_SEND_HEADER_4,
        UART_SEND_DATA,
        UART_WAIT_TX
    );
    signal logger_state : logger_state_type := WAIT_PULSE;
    signal return_state : logger_state_type := WAIT_PULSE;

    -- UART Serializer Signals
    signal uart_tx_r      : STD_LOGIC := '1';
    signal uart_busy      : STD_LOGIC := '0';
    signal uart_start     : STD_LOGIC := '0';
    signal tx_char        : STD_LOGIC_VECTOR(7 downto 0) := x"00";
    signal uart_shift_reg : STD_LOGIC_VECTOR(10 downto 0) := (others => '1');
    signal baud_cnt       : integer range 0 to 1023 := 0;
    signal bit_idx        : integer range 0 to 10 := 0;

begin

    uart_tx <= uart_tx_r;
    busy    <= '0' when logger_state = WAIT_PULSE else '1';

    process(clk100mhz)
    begin
        if rising_edge(clk100mhz) then
            -- UART Serializer Engine (8N1)
            if uart_start = '1' then
                uart_shift_reg <= "11" & tx_char & '0'; -- Stop bits + data + start bit
                bit_idx        <= 0;
                baud_cnt       <= 0;
                uart_busy      <= '1';
                uart_tx_r      <= '0';
            elsif uart_busy = '1' then
                uart_tx_r <= uart_shift_reg(0);
                if baud_cnt = BAUD_RATE_TICKS - 1 then
                    baud_cnt <= 0;
                    if bit_idx = 10 then
                        uart_busy <= '0';
                    else
                        uart_shift_reg <= '1' & uart_shift_reg(10 downto 1);
                        bit_idx        <= bit_idx + 1;
                    end if;
                else
                    baud_cnt <= baud_cnt + 1;
                end if;
            else
                uart_tx_r <= '1';
            end if;

            uart_start <= '0';

            -- Line Capture & Serialization State Machine
            case logger_state is
                when WAIT_PULSE =>
                    if pulse_trigger = '1' then
                        ram_write_idx <= 0;
                        logger_state  <= CAPTURE_SAMPLES;
                    end if;

                when CAPTURE_SAMPLES =>
                    if sample_valid = '1' then
                        adc_ram(ram_write_idx) <= sample_data;
                        if ram_write_idx = 1023 then
                            ram_read_idx <= 0;
                            logger_state <= UART_SEND_HEADER_1;
                        else
                            ram_write_idx <= ram_write_idx + 1;
                        end if;
                    end if;

                when UART_SEND_HEADER_1 =>
                    if uart_busy = '0' then
                        tx_char      <= x"FF";
                        uart_start   <= '1';
                        return_state <= UART_SEND_HEADER_2;
                        logger_state <= UART_WAIT_TX;
                    end if;

                when UART_SEND_HEADER_2 =>
                    if uart_busy = '0' then
                        tx_char      <= x"AA";
                        uart_start   <= '1';
                        return_state <= UART_SEND_HEADER_3;
                        logger_state <= UART_WAIT_TX;
                    end if;

                when UART_SEND_HEADER_3 =>
                    if uart_busy = '0' then
                        tx_char      <= x"55";
                        uart_start   <= '1';
                        return_state <= UART_SEND_HEADER_4;
                        logger_state <= UART_WAIT_TX;
                    end if;

                when UART_SEND_HEADER_4 =>
                    if uart_busy = '0' then
                        tx_char      <= x"00";
                        uart_start   <= '1';
                        return_state <= UART_SEND_DATA;
                        logger_state <= UART_WAIT_TX;
                    end if;

                when UART_SEND_DATA =>
                    if uart_busy = '0' then
                        tx_char    <= adc_ram(ram_read_idx);
                        uart_start <= '1';
                        if ram_read_idx = 1023 then
                            return_state <= WAIT_PULSE;
                        else
                            ram_read_idx <= ram_read_idx + 1;
                            return_state <= UART_SEND_DATA;
                        end if;
                        logger_state <= UART_WAIT_TX;
                    end if;

                when UART_WAIT_TX =>
                    if uart_busy = '1' then
                        logger_state <= return_state;
                    end if;

            end case;
        end if;
    end process;

end Behavioral;
