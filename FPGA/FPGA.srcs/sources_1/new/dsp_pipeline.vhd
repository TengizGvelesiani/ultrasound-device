library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
use IEEE.NUMERIC_STD.ALL;

-- ============================================================================
-- Module Name: dsp_pipeline
-- Description: Ultrasound Digital Signal Processing, Demodulation & Envelope Extraction.
--              Generates the 20 MHz ADC clock, performs DC offset cancellation,
--              5 MHz quadrature demodulation (I/Q), low-pass FIR filtering,
--              CORDIC Cartesian-to-Polar envelope detection, log dynamic range
--              compression via BRAM LUT, and 4:1 peak-hold decimation (5 MSPS).
-- ============================================================================
entity dsp_pipeline is
    Port (
        clk100mhz    : in  STD_LOGIC;

        -- Hardware Parallel ADC Interface
        adc_data     : in  STD_LOGIC_VECTOR(12 downto 1);
        otr          : in  STD_LOGIC;
        adc_clk      : out STD_LOGIC;

        -- Decimated Baseband Envelope Output (5 MSPS)
        sample_out   : out STD_LOGIC_VECTOR(7 downto 0);
        sample_valid : out STD_LOGIC
    );
end dsp_pipeline;

architecture Behavioral of dsp_pipeline is

    -- Xilinx IP Component Declarations
    component fir_compiler_0 is
        port (
            aclk               : in  STD_LOGIC;
            s_axis_data_tvalid : in  STD_LOGIC;
            s_axis_data_tdata  : in  STD_LOGIC_VECTOR(15 downto 0);
            m_axis_data_tvalid : out STD_LOGIC;
            m_axis_data_tdata  : out STD_LOGIC_VECTOR(23 downto 0)
        );
    end component;

    component cordic_0 is
        port (
            aclk                    : in  STD_LOGIC;
            s_axis_cartesian_tvalid : in  STD_LOGIC;
            s_axis_cartesian_tdata  : in  STD_LOGIC_VECTOR(47 downto 0); 
            m_axis_dout_tvalid      : out STD_LOGIC;
            m_axis_dout_tdata       : out STD_LOGIC_VECTOR(47 downto 0) 
        );
    end component;

    component blk_mem_gen_0 is
        port (
            clka  : in  STD_LOGIC;
            addra : in  STD_LOGIC_VECTOR(11 downto 0);
            douta : out STD_LOGIC_VECTOR(7 downto 0)
        );
    end component;

    -- ADC Clock Generation & Bit Mapping
    signal adc_clk_div    : STD_LOGIC := '0';
    signal adc_clk_cnt    : integer range 0 to 4 := 0; 
    signal adc_word       : STD_LOGIC_VECTOR(11 downto 0);

    -- DC Offset Tracking & Baseline Subtraction
    signal dc_baseline    : signed(23 downto 0) := (others => '0');
    signal dc_blocked     : signed(15 downto 0) := (others => '0');

    -- 5 MHz Quadrature Mixer (Fs/4 Demodulation)
    signal demod_seq      : integer range 0 to 3 := 0;
    signal i_demod        : STD_LOGIC_VECTOR(15 downto 0) := (others => '0');
    signal q_demod        : STD_LOGIC_VECTOR(15 downto 0) := (others => '0');
    signal demod_valid    : STD_LOGIC := '0';

    -- Low-Pass FIR Filter Signals
    signal fir_i_valid    : STD_LOGIC;
    signal fir_q_valid    : STD_LOGIC;
    signal fir_i_data     : STD_LOGIC_VECTOR(23 downto 0);
    signal fir_q_data     : STD_LOGIC_VECTOR(23 downto 0);

    -- CORDIC Envelope Magnitude Signals
    signal cordic_in_data : STD_LOGIC_VECTOR(47 downto 0);
    signal cordic_valid   : STD_LOGIC;
    signal cordic_dout    : STD_LOGIC_VECTOR(47 downto 0);
    signal cordic_mag     : STD_LOGIC_VECTOR(23 downto 0);

    -- Logarithmic Compression LUT Signals
    signal rom_addr       : STD_LOGIC_VECTOR(11 downto 0);
    signal rom_out        : STD_LOGIC_VECTOR(7 downto 0);
    signal rom_valid_1    : STD_LOGIC := '0';
    signal rom_valid_2    : STD_LOGIC := '0';

    -- 4:1 Peak-Hold Decimator (20 MHz -> 5 MHz)
    signal decim_cnt      : integer range 0 to 3 := 0;
    signal peak_hold      : unsigned(7 downto 0) := (others => '0');
    signal save_sample_r  : STD_LOGIC := '0';
    signal sample_out_r   : STD_LOGIC_VECTOR(7 downto 0) := (others => '0');

begin

    adc_clk      <= adc_clk_div;
    sample_out   <= sample_out_r;
    sample_valid <= save_sample_r;

    -- Descramble ADC hardware pin mapping
    adc_word <= adc_data(1)  & adc_data(2)  & adc_data(3)  & adc_data(4)  &
                adc_data(5)  & adc_data(6)  & adc_data(7)  & adc_data(8)  &
                adc_data(9)  & adc_data(10) & adc_data(11) & adc_data(12);

    -- FIR Filter Instantiations
    inst_fir_i: fir_compiler_0 port map (
        aclk               => clk100mhz,
        s_axis_data_tvalid => demod_valid,
        s_axis_data_tdata  => i_demod,
        m_axis_data_tvalid => fir_i_valid,
        m_axis_data_tdata  => fir_i_data
    );

    inst_fir_q: fir_compiler_0 port map (
        aclk               => clk100mhz,
        s_axis_data_tvalid => demod_valid,
        s_axis_data_tdata  => q_demod,
        m_axis_data_tvalid => fir_q_valid,
        m_axis_data_tdata  => fir_q_data
    );

    -- CORDIC Magnitude Instantiation (Cartesian -> Polar)
    cordic_in_data <= fir_q_data & fir_i_data;

    inst_cordic: cordic_0 port map (
        aclk                    => clk100mhz,
        s_axis_cartesian_tvalid => fir_i_valid,
        s_axis_cartesian_tdata  => cordic_in_data,
        m_axis_dout_tvalid      => cordic_valid,
        m_axis_dout_tdata       => cordic_dout
    );

    cordic_mag <= cordic_dout(23 downto 0);

    -- Clamp upper bits to prevent dynamic range wrap-around before log table lookup
    rom_addr <= x"FFF" when cordic_mag(23 downto 17) /= "0000000" else cordic_mag(16 downto 5);

    -- Log Compression ROM LUT Instantiation
    inst_rom: blk_mem_gen_0 port map (
        clka  => clk100mhz,
        addra => rom_addr,
        douta => rom_out
    );

    -- DSP Processing, Demodulation & Decimation Pipeline
    process(clk100mhz)
        variable sample_signed : signed(15 downto 0);
    begin
        if rising_edge(clk100mhz) then
            demod_valid   <= '0';
            save_sample_r <= '0';

            -- Generate 20 MHz ADC Clock from 100 MHz (div 5)
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

            -- Sample on 20 MHz boundary
            if adc_clk_cnt = 4 then
                sample_signed := signed(resize(unsigned(adc_word), 16)); 
                dc_baseline   <= dc_baseline + (sample_signed - dc_baseline(23 downto 8));
                dc_blocked    <= sample_signed - dc_baseline(23 downto 8);

                -- Fs/4 Quadrature Demodulation (+I, +Q, -I, -Q)
                if demod_seq = 0 then
                    i_demod <= STD_LOGIC_VECTOR(dc_blocked);
                    q_demod <= (others => '0');
                elsif demod_seq = 1 then
                    i_demod <= (others => '0');
                    q_demod <= STD_LOGIC_VECTOR(dc_blocked);
                elsif demod_seq = 2 then
                    i_demod <= STD_LOGIC_VECTOR(-dc_blocked);
                    q_demod <= (others => '0');
                elsif demod_seq = 3 then
                    i_demod <= (others => '0');
                    q_demod <= STD_LOGIC_VECTOR(-dc_blocked);
                end if;

                if demod_seq = 3 then 
                    demod_seq <= 0; 
                else 
                    demod_seq <= demod_seq + 1; 
                end if;

                demod_valid <= '1';
            end if;

            -- ROM Latency Alignment Pipelines
            rom_valid_2 <= rom_valid_1;
            rom_valid_1 <= cordic_valid;

            -- 4:1 Peak-Detect Decimation (20 MSPS -> 5 MSPS)
            if rom_valid_2 = '1' then
                if unsigned(rom_out) > peak_hold then
                    peak_hold <= unsigned(rom_out);
                end if;

                if decim_cnt = 3 then
                    save_sample_r <= '1';
                    sample_out_r  <= STD_LOGIC_VECTOR(peak_hold);
                    decim_cnt     <= 0;
                else
                    decim_cnt <= decim_cnt + 1;
                end if;
            end if;

            if save_sample_r = '1' then
                peak_hold <= (others => '0');
            end if;

        end if;
    end process;

end Behavioral;
