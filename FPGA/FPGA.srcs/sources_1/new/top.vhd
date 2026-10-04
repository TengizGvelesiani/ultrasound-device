library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
use IEEE.NUMERIC_STD.ALL;

entity pulser_rtz is
    Port (
        CLK100MHZ : in  STD_LOGIC;

        -- hardware interfaces
        relay     : out STD_LOGIC;
        oe        : out STD_LOGIC;
        ina       : out STD_LOGIC;
        inb       : out STD_LOGIC;

        rgb_led   : out STD_LOGIC_VECTOR(5 downto 0);

        cs        : out STD_LOGIC;
        sck       : out STD_LOGIC;
        sci       : out STD_LOGIC;

        clk       : out STD_LOGIC;
        adc_data  : in  STD_LOGIC_VECTOR(12 downto 1);
        otr       : in  STD_LOGIC;

        uart_tx   : out STD_LOGIC;
        test_out  : out STD_LOGIC
    );
end pulser_rtz;

architecture Behavioral of pulser_rtz is

    component fir_compiler_0 is
      port (
        aclk : in std_logic;
        s_axis_data_tvalid : in std_logic;
        s_axis_data_tdata : in std_logic_vector(15 downto 0);
        m_axis_data_tvalid : out std_logic;
        m_axis_data_tdata : out std_logic_vector(23 downto 0)
      );
    end component;

    component cordic_0 is
      port (
        aclk : in std_logic;
        s_axis_cartesian_tvalid : in std_logic;
        s_axis_cartesian_tdata : in std_logic_vector(47 downto 0); 
        m_axis_dout_tvalid : out std_logic;
        m_axis_dout_tdata : out std_logic_vector(47 downto 0) 
      );
    end component;

    component blk_mem_gen_0 is
      port (
        clka : in std_logic;
        addra : in std_logic_vector(11 downto 0);
        douta : out std_logic_vector(7 downto 0)
      );
    end component;

    -- timing constants
    constant POWERON_HOLD_CYCLES : integer := 100_000;   -- startup delay
    constant RELAY_SETTLE_CYCLES : integer := 5_000_000; -- relay debounce & charging
    constant ENABLE_HOLD_CYCLES  : integer := 10_000;    -- prepulse delay

    constant STRIKE_CYCLES       : integer := 6;         -- 60ns pmos strike
    constant DEAD_CYCLES         : integer := 4;         -- 40ns dead time
    constant DAMP_CYCLES         : integer := 6;         -- 60ns nmos damp
    constant ACTIVE_DAMP_CYCLES  : integer := 50;        -- post-burst ringing damp
    constant BURST_COUNT         : integer := 3;
    
    constant PRF_CYCLES          : integer := 1_000_000; -- 100hz prf
    constant IDLE_CYCLES         : integer := PRF_CYCLES - (BURST_COUNT * (STRIKE_CYCLES + DEAD_CYCLES * 2 + DAMP_CYCLES) + ACTIVE_DAMP_CYCLES);

    type state_type is (POWER_ON, RELAY_SETTLE, DRIVER_ENABLE, IDLE, STRIKE, DEAD_TIME_1, DAMP, DEAD_TIME_2, ACTIVE_DAMP);
    signal current_state : state_type := POWER_ON;
    signal last_state    : state_type := POWER_ON;
    signal cycle_counter : integer range 0 to RELAY_SETTLE_CYCLES := 0;
    signal pulse_counter : integer range 0 to 49 := 0;
    signal burst_counter : integer range 0 to 15 := 0;
    signal heartbeat     : STD_LOGIC := '0';
    signal relay_r, oe_r, ina_r, inb_r : STD_LOGIC := '0';

    signal adc_clk_div    : std_logic := '0';
    signal adc_clk_cnt    : integer range 0 to 4 := 0; 
    signal adc_word       : std_logic_vector(11 downto 0);
    
    signal dc_baseline    : signed(23 downto 0) := (others => '0');
    signal dc_blocked     : signed(15 downto 0) := (others => '0');
    
    signal demod_seq      : integer range 0 to 3 := 0;
    signal i_demod        : std_logic_vector(15 downto 0) := (others => '0');
    signal q_demod        : std_logic_vector(15 downto 0) := (others => '0');
    signal demod_valid    : std_logic := '0';
    
    signal fir_i_valid, fir_q_valid : std_logic;
    signal fir_i_data, fir_q_data   : std_logic_vector(23 downto 0);
    
    signal cordic_in_data : std_logic_vector(47 downto 0);
    signal cordic_valid   : std_logic;
    signal cordic_dout    : std_logic_vector(47 downto 0);
    signal cordic_mag     : std_logic_vector(23 downto 0);
    
    signal rom_addr       : std_logic_vector(11 downto 0);
    signal rom_out        : std_logic_vector(7 downto 0);
    signal rom_valid_1    : std_logic := '0';
    signal rom_valid_2    : std_logic := '0';
    
    signal decim_cnt      : integer range 0 to 3 := 0;
    signal peak_hold      : unsigned(7 downto 0) := (others => '0');
    signal save_sample    : std_logic := '0';

    -- uart logger
    constant BAUD_RATE_TICKS : integer := 50; 
    
    type ram_type is array (0 to 1023) of std_logic_vector(7 downto 0);
    signal adc_ram : ram_type := (others => (others => '0'));
    signal ram_write_idx  : integer range 0 to 1023 := 0;
    signal ram_read_idx   : integer range 0 to 1023 := 0;
    
    type logger_state_type is (WAIT_PULSE, CAPTURE_SAMPLES, UART_SEND_HEADER_1, UART_SEND_HEADER_2, UART_SEND_HEADER_3, UART_SEND_HEADER_4, UART_SEND_DATA, UART_WAIT_TX);
    signal logger_state : logger_state_type := WAIT_PULSE;
    signal uart_busy      : std_logic := '0';
    signal uart_start     : std_logic := '0';
    signal tx_char        : std_logic_vector(7 downto 0) := x"00";
    signal uart_shift_reg : std_logic_vector(10 downto 0) := (others => '1');
    signal baud_cnt       : integer range 0 to 1023 := 0;
    signal bit_idx        : integer range 0 to 10 := 0;
    signal return_state   : logger_state_type;

    signal spi_state   : integer range 0 to 2 := 0;
    signal spi_bit_idx : integer range 0 to 15 := 15;
    signal spi_clk_cnt : integer := 0;
    signal cs_r, sck_r, sci_r : std_logic := '0';
    signal spi_start    : std_logic := '0';
    signal spi_data_reg : std_logic_vector(15 downto 0) := x"3080"; 
    signal tgc_timer : integer := 0;
    signal tgc_step_cnt : integer range 0 to 255 := 0;
    signal tgc_value : integer range 0 to 4095 := 128;
    signal spi_init_done : std_logic := '0';
    signal test_pulse : std_logic := '0';

begin

    relay <= relay_r; oe <= oe_r; ina <= ina_r; inb <= inb_r;
    clk <= adc_clk_div; test_out <= test_pulse; cs <= cs_r; sck <= sck_r; sci <= sci_r;
    rgb_led(1) <= heartbeat; rgb_led(4) <= relay_r; 
    rgb_led(0) <= '0'; rgb_led(2) <= '0'; rgb_led(3) <= '0'; rgb_led(5) <= '0';

    -- adc wire mapping
    adc_word <= adc_data(1)  & adc_data(2)  & adc_data(3)  & adc_data(4)  &
                adc_data(5)  & adc_data(6)  & adc_data(7)  & adc_data(8)  &
                adc_data(9)  & adc_data(10) & adc_data(11) & adc_data(12);

    inst_fir_i: fir_compiler_0 port map (
        aclk => CLK100MHZ,
        s_axis_data_tvalid => demod_valid,
        s_axis_data_tdata => i_demod,
        m_axis_data_tvalid => fir_i_valid,
        m_axis_data_tdata => fir_i_data
    );

    inst_fir_q: fir_compiler_0 port map (
        aclk => CLK100MHZ,
        s_axis_data_tvalid => demod_valid,
        s_axis_data_tdata => q_demod,
        m_axis_data_tvalid => fir_q_valid,
        m_axis_data_tdata => fir_q_data
    );

    cordic_in_data <= fir_q_data & fir_i_data;

    inst_cordic: cordic_0 port map (
        aclk => CLK100MHZ,
        s_axis_cartesian_tvalid => fir_i_valid,
        s_axis_cartesian_tdata => cordic_in_data,
        m_axis_dout_tvalid => cordic_valid,
        m_axis_dout_tdata => cordic_dout
    );

    cordic_mag <= cordic_dout(23 downto 0);

    -- clamp upper bits to prevent wrap-around before log compression
    rom_addr <= x"FFF" when cordic_mag(23 downto 17) /= "0000000" else cordic_mag(16 downto 5);

    inst_rom: blk_mem_gen_0 port map (
        clka => CLK100MHZ,
        addra => rom_addr,
        douta => rom_out
    );

    -- dsp pipeline
    process(CLK100MHZ)
        variable sample_signed : signed(15 downto 0);
    begin
        if rising_edge(CLK100MHZ) then
            demod_valid <= '0';
            save_sample <= '0';

            if adc_clk_cnt = 4 then
                adc_clk_cnt <= 0;
            else
                adc_clk_cnt <= adc_clk_cnt + 1;
            end if;
            
            if adc_clk_cnt < 2 then
                adc_clk_div <= '1';
            else
                adc_clk_div <= '0';
            end if;

            if adc_clk_cnt = 4 then
                sample_signed := signed(resize(unsigned(adc_word), 16)); 
                dc_baseline <= dc_baseline + (sample_signed - dc_baseline(23 downto 8));
                dc_blocked <= sample_signed - dc_baseline(23 downto 8);
                
                if demod_seq = 0 then
                    i_demod <= std_logic_vector(dc_blocked);
                    q_demod <= (others => '0');
                elsif demod_seq = 1 then
                    i_demod <= (others => '0');
                    q_demod <= std_logic_vector(dc_blocked);
                elsif demod_seq = 2 then
                    i_demod <= std_logic_vector(-dc_blocked);
                    q_demod <= (others => '0');
                elsif demod_seq = 3 then
                    i_demod <= (others => '0');
                    q_demod <= std_logic_vector(-dc_blocked);
                end if;
                
                if demod_seq = 3 then demod_seq <= 0; else demod_seq <= demod_seq + 1; end if;
                demod_valid <= '1';
            end if;

            rom_valid_2 <= rom_valid_1;
            rom_valid_1 <= cordic_valid;

            if rom_valid_2 = '1' then
                if unsigned(rom_out) > peak_hold then
                    peak_hold <= unsigned(rom_out);
                end if;
                
                if decim_cnt = 3 then
                    save_sample <= '1';
                    decim_cnt <= 0;
                else
                    decim_cnt <= decim_cnt + 1;
                end if;
            end if;
            
            if save_sample = '1' then
                peak_hold <= (others => '0');
            end if;

        end if;
    end process;

    -- serial data transmission
    process(CLK100MHZ)
    begin
        if rising_edge(CLK100MHZ) then
            if uart_start = '1' then
                uart_shift_reg <= "11" & tx_char & '0';
                bit_idx <= 0; baud_cnt <= 0; uart_busy <= '1'; uart_tx <= '0';
            elsif uart_busy = '1' then
                uart_tx <= uart_shift_reg(0);
                if baud_cnt = BAUD_RATE_TICKS-1 then
                    baud_cnt <= 0;
                    if bit_idx = 10 then
                        uart_busy <= '0';
                    else
                        uart_shift_reg <= '1' & uart_shift_reg(10 downto 1);
                        bit_idx <= bit_idx + 1;
                    end if;
                else
                    baud_cnt <= baud_cnt + 1;
                end if;
            else
                uart_tx <= '1';
            end if;
            uart_start <= '0';

            case logger_state is
                when WAIT_PULSE =>
                    if current_state = STRIKE and last_state = IDLE then
                        ram_write_idx <= 0;
                        logger_state <= CAPTURE_SAMPLES;
                    end if;

                when CAPTURE_SAMPLES =>
                    if save_sample = '1' then
                        adc_ram(ram_write_idx) <= std_logic_vector(peak_hold);
                        if ram_write_idx = 1023 then
                            ram_read_idx <= 0;
                            logger_state <= UART_SEND_HEADER_1;
                        else
                            ram_write_idx <= ram_write_idx + 1;
                        end if;
                    end if;

                when UART_SEND_HEADER_1 =>
                    if uart_busy = '0' then tx_char <= x"FF"; uart_start <= '1'; return_state <= UART_SEND_HEADER_2; logger_state <= UART_WAIT_TX; end if;
                when UART_SEND_HEADER_2 =>
                    if uart_busy = '0' then tx_char <= x"AA"; uart_start <= '1'; return_state <= UART_SEND_HEADER_3; logger_state <= UART_WAIT_TX; end if;
                when UART_SEND_HEADER_3 =>
                    if uart_busy = '0' then tx_char <= x"55"; uart_start <= '1'; return_state <= UART_SEND_HEADER_4; logger_state <= UART_WAIT_TX; end if;
                when UART_SEND_HEADER_4 =>
                    if uart_busy = '0' then tx_char <= x"00"; uart_start <= '1'; return_state <= UART_SEND_DATA; logger_state <= UART_WAIT_TX; end if;

                when UART_SEND_DATA =>
                    if uart_busy = '0' then
                        tx_char <= adc_ram(ram_read_idx);
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
                    if uart_busy = '1' then logger_state <= return_state; end if;

            end case;
        end if;
    end process;

    -- dac spi driver
    process(CLK100MHZ)
    begin
        if rising_edge(CLK100MHZ) then
            if spi_state = 0 then
                cs_r <= '1';
                sck_r <= '0';
                if spi_init_done = '0' and spi_start = '0' and cycle_counter > 100 then
                    spi_clk_cnt <= 0;
                    spi_bit_idx <= 15;
                    spi_state <= 1;
                elsif spi_start = '1' then
                    spi_clk_cnt <= 0;
                    spi_bit_idx <= 15;
                    spi_state <= 1;
                end if;
            elsif spi_state = 1 then
                if spi_clk_cnt = 0 then
                    cs_r <= '0';
                    sci_r <= spi_data_reg(spi_bit_idx);
                    spi_clk_cnt <= spi_clk_cnt + 1;
                elsif spi_clk_cnt = 4 then
                    sck_r <= '1';
                    spi_clk_cnt <= spi_clk_cnt + 1;
                elsif spi_clk_cnt = 9 then
                    sck_r <= '0';
                    spi_clk_cnt <= 0;
                    if spi_bit_idx = 0 then
                        cs_r <= '1';
                        spi_state <= 2;
                    else
                        spi_bit_idx <= spi_bit_idx - 1;
                    end if;
                else
                    spi_clk_cnt <= spi_clk_cnt + 1;
                end if;
            elsif spi_state = 2 then
                cs_r <= '1';
                spi_init_done <= '1';
                spi_state <= 0;
            end if;
        end if;
    end process;

    -- tgc sweep generator
    process(CLK100MHZ)
    begin
        if rising_edge(CLK100MHZ) then
            spi_start <= '0';
            if current_state = STRIKE and last_state = IDLE then
                tgc_timer <= 1;
                tgc_step_cnt <= 0;
                tgc_value <= 128;
                spi_data_reg <= x"3080";
                spi_start <= '1';
            elsif tgc_timer > 0 then
                if tgc_timer < 25000 then
                    tgc_timer <= tgc_timer + 1;
                else
                    tgc_timer <= 0;
                end if;
                
                if tgc_step_cnt = 199 then
                    tgc_step_cnt <= 0;
                    if tgc_value < 3000 then
                        tgc_value <= tgc_value + 75;
                        spi_data_reg <= x"3" & std_logic_vector(to_unsigned(tgc_value, 12));
                        spi_start <= '1';
                    end if;
                else
                    tgc_step_cnt <= tgc_step_cnt + 1;
                end if;
            end if;
        end if;
    end process;

    -- high voltage pulser statemachine
    process(CLK100MHZ)
    begin
        if rising_edge(CLK100MHZ) then
            oe_r <= '1';
            ina_r <= '0';
            inb_r <= '1';
            last_state <= current_state;
            
            case current_state is
                when POWER_ON =>
                    relay_r <= '0';
                    oe_r <= '0';
                    ina_r <= '0';
                    inb_r <= '1';
                    if cycle_counter >= POWERON_HOLD_CYCLES - 1 and spi_init_done = '1' then
                        cycle_counter <= 0;
                        current_state <= RELAY_SETTLE;
                    elsif cycle_counter < POWERON_HOLD_CYCLES - 1 then
                        cycle_counter <= cycle_counter + 1;
                    end if;
                    
                when RELAY_SETTLE =>
                    relay_r <= '1';
                    oe_r <= '0';
                    ina_r <= '0';
                    inb_r <= '1';
                    if cycle_counter >= RELAY_SETTLE_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= DRIVER_ENABLE;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;
                    
                when DRIVER_ENABLE =>
                    relay_r <= '1';
                    oe_r <= '1';
                    ina_r <= '0';
                    inb_r <= '1';
                    if cycle_counter >= ENABLE_HOLD_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= IDLE;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;
                    
                when IDLE =>
                    relay_r <= '1';
                    oe_r <= '1';
                    ina_r <= '0';
                    inb_r <= '1';
                    if cycle_counter >= IDLE_CYCLES - 1 then
                        cycle_counter <= 0;
                        burst_counter <= 0;
                        current_state <= STRIKE;
                        if pulse_counter >= 49 then
                            pulse_counter <= 0;
                            heartbeat <= not heartbeat;
                        else
                            pulse_counter <= pulse_counter + 1;
                        end if;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;
                    
                when STRIKE =>
                    relay_r <= '1';
                    oe_r <= '1';
                    ina_r <= '1';
                    inb_r <= '1';
                    if cycle_counter >= STRIKE_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= DEAD_TIME_1;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;
                    
                when DEAD_TIME_1 =>
                    relay_r <= '1';
                    oe_r <= '1';
                    ina_r <= '0';
                    inb_r <= '1';
                    if cycle_counter >= DEAD_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= DAMP;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;
                    
                when DAMP =>
                    relay_r <= '1';
                    oe_r <= '1';
                    ina_r <= '0';
                    inb_r <= '0';
                    if cycle_counter >= DAMP_CYCLES - 1 then
                        cycle_counter <= 0;
                        if burst_counter >= BURST_COUNT - 1 then
                            current_state <= ACTIVE_DAMP;
                        else
                            burst_counter <= burst_counter + 1;
                            current_state <= DEAD_TIME_2;
                        end if;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;
                    
                when DEAD_TIME_2 =>
                    relay_r <= '1';
                    oe_r <= '1';
                    ina_r <= '0';
                    inb_r <= '1';
                    if cycle_counter >= DEAD_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= STRIKE;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;
                    
                when ACTIVE_DAMP =>
                    relay_r <= '1';
                    oe_r <= '1';
                    ina_r <= '0';
                    inb_r <= '0';
                    if cycle_counter >= ACTIVE_DAMP_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= IDLE;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;
                    
                when others =>
                    relay_r <= '0';
                    oe_r <= '0';
                    ina_r <= '0';
                    inb_r <= '1';
                    current_state <= POWER_ON;
            end case;
        end if;
    end process;

end Behavioral;
